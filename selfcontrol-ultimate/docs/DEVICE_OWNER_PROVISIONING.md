# Device Owner Provisioning — Samsung Android 15 (rooté)

Méthode pour poser SelfControl Ultimate comme Device Owner sur un téléphone Samsung Android 15+ déjà setup, avec comptes Google présents, sans factory reset.

## Contexte

Sur **Samsung OneUI 7 / Android 15**, la commande standard `dpm set-device-owner` est refusée par Knox dès que :
- Un ou plusieurs comptes utilisateur (Google notamment) sont présents
- OU le device a déjà passé le setup wizard (`user_setup_complete=1`)

Et ça **même avec `android:testOnly="true"`** dans le manifest. C'est une régression spécifique à OneUI 7 — sur Android 13 (OneUI 5) la voie testOnly fonctionne.

## Solution : injection ABX directe

Au lieu de demander à Android d'enregistrer le DO via la couche shell (qui est bloquée par Knox), on **écrit directement le fichier `/data/system/device_owner_2.xml`** au format binaire ABX qu'Android attend, puis on reboot pour qu'il soit chargé au démarrage.

Cette méthode bypass tous les checks parce qu'Android lit le fichier comme s'il l'avait écrit lui-même — Knox ne s'interpose pas.

### Prérequis

- Téléphone rooté (Magisk)
- ADB autorisé sur le PC
- APK SelfControl Ultimate installée avec `android:testOnly="true"` dans le manifest
- Outil `xml2abx` disponible sur le téléphone (présent par défaut sur Android 12+)

### Procédure complète

**1. Vérifier l'état initial**
```bash
adb -s <SERIAL> shell "su -c 'id'"  # doit renvoyer uid=0
adb -s <SERIAL> shell "pm list packages | grep com.jo.selfcontrol.ultimate"  # APK installée
adb -s <SERIAL> shell "ls /system/bin/xml2abx"  # outil de conversion présent
```

**2. Générer le XML du Device Owner**

Format minimal qui marche sur Android 15 (matché sur ce qu'Android écrit nativement) :
```xml
<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<root>
<device-owner component="com.jo.selfcontrol.ultimate/com.jo.selfcontrol.ultimate.AdminReceiver">
<isPoOrganizationOwnedDevice />
</device-owner>
<device-owner-context userId="0" />
</root>
```

Sauvegarder dans `device_owner_2.xml` (sur PC).

**3. Pousser, convertir, installer**
```bash
adb -s <SERIAL> push device_owner_2.xml /data/local/tmp/device_owner_2.xml

adb -s <SERIAL> shell "su -c '
  xml2abx /data/local/tmp/device_owner_2.xml /data/local/tmp/device_owner_2.abx &&
  cp /data/local/tmp/device_owner_2.abx /data/system/device_owner_2.xml &&
  chown system:system /data/system/device_owner_2.xml &&
  chmod 600 /data/system/device_owner_2.xml
'"
```

**4. Reboot**
```bash
adb -s <SERIAL> reboot
```
Attendre la fin du boot (~60-90s).

**5. Vérifier que le DO est chargé**
```bash
adb -s <SERIAL> shell "dumpsys device_policy 2>/dev/null | grep -A5 'Device Owner'"
```
Sortie attendue :
```
Device Owner:
  admin=ComponentInfo{com.jo.selfcontrol.ultimate/com.jo.selfcontrol.ultimate.AdminReceiver}
  package=com.jo.selfcontrol.ultimate
  ...
Device Owner Type: 0
```

**6. Activer Device Admin**

À ce stade le DO est déclaré mais l'**Active Admin** est dans un état "split" (registre DO ✓ mais admin pas dans la liste active). On l'active via root :
```bash
adb -s <SERIAL> shell "su -c 'dpm set-active-admin --user 0 com.jo.selfcontrol.ultimate/com.jo.selfcontrol.ultimate.AdminReceiver'"
```
Doit renvoyer `Success: Active admin set to component ...`.

**7. Lancer l'app pour appliquer les policies**
```bash
adb -s <SERIAL> shell "am start -n com.jo.selfcontrol.ultimate/.MainActivity"
```

`LimitService.onCreate()` détecte `isDeviceOwner()=true` et appelle `applyInitialPolicies()` qui pose :
- `setUninstallBlocked(true)` — uninstall UI grisé
- `setPermissionGrantState(PACKAGE_USAGE_STATS, GRANTED)` — auto-grant stats d'usage
- `addUserRestriction(DISALLOW_INSTALL_APPS)` — blocage installations
- `addUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES)` — blocage sideload
- `addUserRestriction(DISALLOW_FACTORY_RESET)` — blocage factory reset
- `addUserRestriction(DISALLOW_SAFE_BOOT)` — blocage safe mode
- AppOps `GET_USAGE_STATS` via réflexion (pour Android 15 où la voie DPM est restreinte)

**8. Vérifier que tout est appliqué**
```bash
adb -s <SERIAL> shell "dumpsys device_policy 2>/dev/null | grep -iE 'restriction|uninstall|blocked'"
```
Doit lister :
```
PackagePolicyKey{mPolicyKey= packageUninstallBlocked; mPackageName= com.jo.selfcontrol.ultimate}
UserRestrictionPolicyKey userRestriction_no_install_apps
UserRestrictionPolicyKey userRestriction_no_factory_reset
UserRestrictionPolicyKey userRestriction_no_install_unknown_sources
UserRestrictionPolicyKey userRestriction_no_safe_boot
```

Test concret du blocage uninstall :
```bash
adb -s <SERIAL> shell "pm uninstall com.jo.selfcontrol.ultimate"
# Attendu : Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]
```

## Pièges et points d'attention

### NE JAMAIS faire `dpm remove-active-admin` sur un appareil dans cet état

Sur Samsung Android 15 avec comptes, dès qu'on retire l'admin actif, **impossible de le remettre** via les voies standards (Knox bloque `dpm set-device-owner`). On retomberait dans l'état "split" sans solution propre — il faudrait re-injecter l'XML.

Pour itérer sur un APK avec DO actif, utiliser un mécanisme intra-app (broadcast receiver `ALLOW_INSTALL` / `BLOCK_INSTALL` qui appelle `clearUserRestriction(DISALLOW_INSTALL_APPS)` puis le rétablit) plutôt que retirer l'admin :
```bash
adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
# adb install -r -t app-debug.apk
adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
```

### `enforceA11YReEnable` échoue silencieusement sur Android 15

Sur SDK 35+, `setSecureSetting(ENABLED_ACCESSIBILITY_SERVICES)` est refusée même pour le DO (`Permission denial: Device owners cannot update enabled_accessibility_services`). Pas grave en pratique : l'AccessibilityService se protège elle-même via interception des `AccessibilityEvent` quand l'utilisateur tente de la désactiver depuis Settings.

### Format ABX est strict

Le plain-text XML écrit dans `/data/system/device_owner_2.xml` n'est PAS lu par Android — il faut le format binaire ABX. C'est pour ça qu'on convertit avec `xml2abx` avant de copier. Vérifier après injection que le fichier commence bien par les magic bytes `ABX\x00` :
```bash
adb shell "su -c 'xxd /data/system/device_owner_2.xml | head -1'"
# Doit afficher : 4142 5800 ...  (= "ABX\0...")
```

### Pas besoin de toucher `device_policies.xml`

Ce fichier est régénéré automatiquement par Android quand le DO appelle ses APIs (setUninstallBlocked, addUserRestriction, etc.). On le laisse tranquille.

## Désactivation propre (emergency backdoor)

Pour retirer le DO et redonner le contrôle au user :
```bash
adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_OWNER --es password "<PASSWORD>"
```

Cela appelle `DeviceOwnerHelper.clearDeviceOwner()` qui :
1. Lève `setUninstallBlocked(false)`
2. Clear toutes les `UserRestriction` posées
3. Appelle `clearDeviceOwnerApp()` — l'app redevient un Device Admin standard
4. L'utilisateur peut ensuite désinstaller via Settings normalement

## Récapitulatif : commandes en une passe

```bash
SERIAL=RZCT30L9EGJ  # adapter
PKG=com.jo.selfcontrol.ultimate
RECEIVER=$PKG/$PKG.AdminReceiver

cat > device_owner_2.xml << EOF
<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<root>
<device-owner component="$RECEIVER">
<isPoOrganizationOwnedDevice />
</device-owner>
<device-owner-context userId="0" />
</root>
EOF

adb -s $SERIAL push device_owner_2.xml /data/local/tmp/
adb -s $SERIAL shell "su -c 'xml2abx /data/local/tmp/device_owner_2.xml /data/local/tmp/do.abx && cp /data/local/tmp/do.abx /data/system/device_owner_2.xml && chown system:system /data/system/device_owner_2.xml && chmod 600 /data/system/device_owner_2.xml'"
adb -s $SERIAL reboot

# attendre le boot...

adb -s $SERIAL shell "su -c 'dpm set-active-admin --user 0 $RECEIVER'"
adb -s $SERIAL shell "am start -n $PKG/.MainActivity"

# vérification
adb -s $SERIAL shell "dumpsys device_policy 2>/dev/null | grep -A3 'Device Owner'"
```

## Cas confirmés

- ✅ Samsung Galaxy A53 (SM_A536B) — OneUI 7 / Android 15 — méthode testée le 2026-05-01
- ⚠️ Samsung Galaxy Tab S7+ (SM_T970) — Android 13 / OneUI 5 — pas besoin de cette méthode, le `dpm set-device-owner` direct fonctionne avec testOnly
