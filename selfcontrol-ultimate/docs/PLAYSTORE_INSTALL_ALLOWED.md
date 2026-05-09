# Autorisation des téléchargements Play Store

> ⚠️ **Obsolète depuis le 2026-05-02.** Cette modification a été reverte — voir `ADB_ONLY_INSTALLS.md`. Le Play Store est de nouveau bloqué (`DISALLOW_INSTALL_APPS` réactivée). Ce document est conservé pour l'historique.

## Contexte

La version initiale de l'app appliquait la restriction `DISALLOW_INSTALL_APPS` dans `applyInitialPolicies()`, ce qui bloquait **tous** les téléchargements depuis le Play Store et l'installation d'APK, y compris depuis des sources officielles.

## Problème

La tablette cible ne pouvait plus télécharger ni installer aucune application depuis le Play Store. La restriction étant posée au niveau Device Owner (DPM), elle ne pouvait pas être levée par l'utilisateur depuis les paramètres.

## Modification apportée

**Fichier :** `app/src/main/java/com/jo/selfcontrol/ultimate/DeviceOwnerHelper.kt`  
**Méthode :** `applyInitialPolicies()` — section 5

**Avant :**
```kotlin
// 5. Prevent any app installation (Play Store + sideloading) — file downloads unaffected
runCatching {
    d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
    d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
}
```

**Après :**
```kotlin
// 5. Block sideloading only — Play Store installs are permitted
runCatching {
    d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
    d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
}
```

### Ce qui change

| Restriction | Avant | Après |
|---|---|---|
| `DISALLOW_INSTALL_APPS` (Play Store) | Bloqué | **Autorisé** |
| `DISALLOW_INSTALL_UNKNOWN_SOURCES` (sideloading) | Bloqué | Bloqué |

Le `clearUserRestriction` (au lieu de simplement supprimer l'`addUserRestriction`) est intentionnel : il lève activement la restriction déjà posée sur la tablette au prochain démarrage du service, sans nécessiter de reboot.

## Déploiement

La restriction `DISALLOW_INSTALL_APPS` étant déjà active sur la tablette, l'installation directe via ADB était bloquée. Procédure suivie :

1. Envoi du broadcast `ALLOW_INSTALL` intégré à l'app pour lever temporairement la restriction :
   ```
   adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -p com.jo.selfcontrol.ultimate
   ```
2. Build du nouvel APK : `.\gradlew assembleDebug`
3. Installation avec le flag `-t` (APK debug marqué `testOnly`) :
   ```
   adb install -r -t app-debug.apk
   ```
4. Redémarrage de l'app sur la tablette → `applyInitialPolicies()` appelé → restriction levée définitivement.
