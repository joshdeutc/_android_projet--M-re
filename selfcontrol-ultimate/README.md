# SelfControl Ultimate
L'édition ultime — système Device Owner + Accessibilité, fusionnés.

> Le module Magisk a été supprimé (cf. `ARCHITECTURE.md`). L'enforcement repose désormais sur le Device Owner natif Android + un AccessibilityService de renfort.

## Procédure d'Installation (Guide Pas-à-pas)
Voici les étapes pour configurer complètement le dispositif.

**1. Construire l'App :**
À la racine du projet :
```powershell
.\gradlew :app:assembleRelease
```
(ou `assembleDebug` si tu as besoin du flag `testOnly` pour la voie de provisioning ABX)

**2. Installer l'APK :**
```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```
Si le DO est déjà actif sur le device, lever d'abord la restriction d'install via le broadcast intra-app (cf. `docs/ADB_ONLY_INSTALLS.md`) :
```powershell
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
```

**3. Activer le Device Admin :**
Ouvrir l'application et accepter la demande "Activer l'administration" via le pop-up.

**4. Provisionner en Device Owner :**
Sur **Android 15 / OneUI 7+ avec comptes Google** : `dpm set-device-owner` est refusé par Knox. Suivre la méthode d'injection ABX décrite dans `docs/DEVICE_OWNER_PROVISIONING.md` (résumé : générer le XML, le convertir en ABX via `xml2abx`, le copier dans `/data/system/device_owner_2.xml`, reboot).

> Le script `provision_device_owner.ps1` à la racine pousse le XML en plain-text — **il ne fonctionne pas sur Android 12+** où le format ABX binaire est exigé. Préférer la procédure manuelle de `DEVICE_OWNER_PROVISIONING.md` jusqu'à ce que le script soit corrigé.

**5. Configurer les limites :**
Le fichier de config est `limits.json`, placé via ADB dans le `filesDir` de l'app :
```powershell
adb push limits.json /sdcard/Download/limits.json
adb shell run-as com.jo.selfcontrol.ultimate cp /sdcard/Download/limits.json files/limits.json
```
(Ou utiliser l'écran de config interne de l'app.) `LimitService` détecte le changement et recharge à chaud — pas besoin de redémarrer le service.

## Backdoors & Dépannage
En cas d'urgence où l'application empêche la moindre action, retirer le Device Owner :
```powershell
.\remove_device_owner.ps1
```
Ou via le broadcast d'urgence protégé par mot de passe :
```powershell
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.REMOVE_OWNER --es password "<PASSWORD>"
```

Pour les apps coincées suspendues (icône grisée, "App paused"), un broadcast force-libère toutes les suspensions posées par notre DO :
```powershell
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.UNSUSPEND_ALL
```
Pour les cas plus complexes (suspension shell root, autre admin), voir `docs/APP_SUSPENSION_LIFECYCLE.md`.

## Documentation détaillée

- `ARCHITECTURE.md` — vue d'ensemble des couches DO + A11Y
- `docs/DEVICE_OWNER_PROVISIONING.md` — pose du DO via injection ABX (Android 15)
- `docs/ADB_ONLY_INSTALLS.md` — politique d'install et workflow `install_xapk.ps1`
- `docs/APP_SUSPENSION_LIFECYCLE.md` — suspend/unsuspend, daily reset, recovery au boot
- `docs/APPWATCHER_SAMSUNG_COMPAT.md` — compat OneUI 5 / OneUI 7 pour la protection A11Y
- `docs/TROUBLESHOOTING_ANDROID_15_INSTALL.md` — `am broadcast -p` et autres pièges A15
- `docs/PLAYSTORE_INSTALL_ALLOWED.md` — *obsolète, conservé pour l'historique*
