# Installations ADB exclusives

## Contexte

Le mode "Play Store autorisé" (cf. `PLAYSTORE_INSTALL_ALLOWED.md`, mai 2026) a été abandonné. Motivation : empêcher tout téléchargement-puis-installation depuis Google Drive, OneDrive, navigateurs, et autres canaux indirects. La règle est désormais : **seul le PC, via ADB, peut installer une app sur les deux appareils**.

## Politique appliquée

`DeviceOwnerHelper.applyInitialPolicies()` (section 5) pose désormais les deux restrictions ensemble :

```kotlin
d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)             // bloque Play Store
d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)  // bloque sideload (Drive, OneDrive, navigateur)
```

Les deux restrictions étant posées par le Device Owner, l'utilisateur ne peut pas les lever depuis Settings.

## Workflow d'installation côté PC

Script : `..\platform-tools\install_xapk.ps1` (à la racine du parent du projet, pas dans `selfcontrol-ultimate/`)

```powershell
.\install_xapk.ps1 -XapkPath "C:\Downloads\app.xapk"
# ou avec ciblage explicite si plusieurs appareils branchés
.\install_xapk.ps1 -XapkPath "C:\Downloads\app.xapk" -Serial RZCT30L9EGJ
```

Le script enchaîne :

1. Broadcast `com.jo.selfcontrol.ultimate.ALLOW_INSTALL` → `DeviceOwnerHelper.setInstallRestrictions(blocked=false)` lève temporairement les deux restrictions.
2. Décompression du `.xapk` (ZIP) dans `%TEMP%\xapk_<random>\`.
3. `adb install-multiple -r -t` sur la base APK + tous les splits trouvés.
4. Si l'XAPK contient `Android\obb\<package>\*.obb`, push vers `/sdcard/Android/obb/<package>/`.
5. **Dans un `finally`** : broadcast `com.jo.selfcontrol.ultimate.BLOCK_INSTALL` pour rétablir les restrictions, puis nettoyage du dossier temp. Le rétablissement passe même si l'install échoue.

## Test rapide du blocage

Une fois la politique appliquée :

| Canal | Comportement attendu |
|---|---|
| Play Store → "Installer" | Bouton grisé / "bloqué par l'admin" |
| Drive → ouvrir un .apk téléchargé | "Installation bloquée" |
| OneDrive → idem | "Installation bloquée" |
| `adb install foo.apk` (sans broadcast) | `INSTALL_FAILED_USER_RESTRICTED` |
| `install_xapk.ps1` | ✅ succès puis re-blocage |

## Backdoor d'urgence

Inchangée : `adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_OWNER --es password "<PASSWORD>"`.
