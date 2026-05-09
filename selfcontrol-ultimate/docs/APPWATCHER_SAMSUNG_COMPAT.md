# AppWatcherService — Compatibilité Samsung OneUI

## Problème initial

Sur **Samsung Android 13 / OneUI 5** (SM-T970), la protection contre la désactivation de
l'accessibilité ne se déclenchait pas, alors qu'elle fonctionnait correctement sur
**Samsung Android 15 / OneUI 7** (SM-A536B).

## Cause racine

`AppWatcherService` protège l'app en interceptant les `AccessibilityEvent` quand l'utilisateur
ouvre l'écran d'accessibilité dans les Paramètres, puis renvoie l'utilisateur à l'accueil
(`goHome()`) si l'app est visible dans la liste.

Le service filtre les events par **nom de package**. Or Samsung a changé d'organisation entre
les versions OneUI :

| Version | Package des Paramètres accessibilité | Classe |
|---------|--------------------------------------|--------|
| OneUI 7 / Android 15 | `com.android.settings` | `Settings$AccessibilitySettingsActivity` |
| OneUI 5 / Android 13 | `com.samsung.accessibility` | `AccessibilityHomepageActivity` |

Sur OneUI 5, Samsung a sorti l'accessibilité du package Settings standard et l'a placée dans
**son propre package dédié** `com.samsung.accessibility`. Le code initial ne filtrait que
`com.android.settings` → les events venant de `com.samsung.accessibility` étaient ignorés
silencieusement → aucune protection ne se déclenchait.

## Deuxième bug : requireDangerKeyword

Sur la page d'accessibilité Samsung (toutes versions), le toggle ON/OFF est un simple switch
sans texte "Désactiver" / "Disable". La logique initiale passait `requireDangerKeyword = true`,
donc elle cherchait ces mots-clés dans les noeuds visibles, ne les trouvait pas, et abandonnait.

Fix : la page accessibilité passe à `requireDangerKeyword = false`. Le simple fait que notre
app soit visible dans la liste suffit à déclencher la protection.

## Fix appliqué

Dans `AppWatcherService.kt` :

```kotlin
// SETTINGS_PACKAGES — packages qui hébergent les pages de paramètres
private val SETTINGS_PACKAGES = setOf(
    "com.android.settings",
    "com.samsung.android.app.routines",
    "com.samsung.android.sm",
    "com.samsung.accessibility"      // OneUI 5 / Android 13 : package dédié
)

// Détection page accessibilité — couvre AOSP et OneUI 5
val isAccessibilityPage =
    className.contains("AccessibilitySettings")
    || className.contains("ToggleAccessibilityService")
    || className.contains("InstalledAccessibilityService")
    || className.contains("AccessibilityHomepageActivity")  // OneUI 5
    || packageName == "com.samsung.accessibility"           // OneUI 5 fallback

if (isAccessibilityPage && packageName in SETTINGS_PACKAGES) {
    checkSettingsForSelfControl(requireDangerKeyword = false)  // pas de keyword requis
}
```

## Conséquence indirecte : perte du Device Owner

Pour déployer ce correctif sur le téléphone Android 15 (DO actif), il a fallu installer une
nouvelle APK. Mais `DISALLOW_INSTALL_APPS` bloquait même l'installation via ADB. La tentative
de contournement via `dpm remove-active-admin` a fait perdre le statut Device Owner — Knox sur
Android 15 refuse `dpm set-device-owner` dès qu'il y a des comptes présents.

**Leçon** : pour déployer une mise à jour APK avec DO actif sur Android 15, utiliser
exclusivement les broadcasts intra-app :

```bash
adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
adb install -r -t app-debug.apk
adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
```

Ces broadcasts (`CommandReceiver`) appellent `setInstallRestrictions(blocked)` via
`DeviceOwnerHelper` sans jamais toucher au statut Device Admin / Device Owner.

## Comportement final

| Situation | Déclenchement |
|-----------|--------------|
| Settings verrouillés + utilisateur ouvre accessibilité (AOSP ou Samsung) | `goHome()` immédiat |
| Settings déverrouillés | bypass via `DelayManager.isSettingsUnlocked()` |
| Utilisateur tente de désactiver via toggle switch Samsung | `goHome()` (pas de keyword requis) |
| Utilisateur ouvre fiche app → bouton Désinstaller | `goHome()` (keyword "Désinstaller" trouvé) |
