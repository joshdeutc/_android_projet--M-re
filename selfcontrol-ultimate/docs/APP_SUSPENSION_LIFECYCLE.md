# Cycle de vie de la suspension d'apps (Device Owner)

Comment SelfControl Ultimate suspend et libère les apps cibles via `DevicePolicyManager.setPackagesSuspended()`, et pourquoi il faut un nettoyage défensif au démarrage.

## Mécanique de base

Quand une limite est dépassée (quota, curfew, jour interdit) :

```kotlin
DeviceOwnerHelper.suspendApp(this, packageName)   // → setPackagesSuspended(true)
suspendedApps.add(packageName)                    // tracking en mémoire
AppWatcherService.blockedApps.add(packageName)    // tracking pour HOME spam fallback
```

Quand la limite n'est plus active (curfew terminé, config retirée) :

```kotlin
DeviceOwnerHelper.unsuspendApp(this, pkg)         // → setPackagesSuspended(false)
suspendedApps.remove(pkg)
AppWatcherService.blockedApps.remove(pkg)
```

## Le piège : `suspendedApps` n'est pas persisté

`suspendedApps` est un `mutableSetOf<String>()` en mémoire dans `LimitService`. Il est vidé à chaque restart du service. **L'état suspendu côté OS, lui, est persisté par Android** — `setPackagesSuspended(true)` survit aux reboots.

Conséquence : quand le service redémarre (boot, kill par le système, mise à jour APK), SelfControl pense que rien n'est suspendu, alors que des apps peuvent l'être au niveau OS. `checkAndUnblockApps()` itère sur `suspendedApps` (vide) → ne voit rien → les apps restent coincées suspendues, **sans aucun moyen automatique de les libérer**.

## Bug historique : daily reset n'unsuspendait pas

Avant ce fix, le bloc « new day detected » dans `enforceLimit()` (`LimitService.kt:262`) faisait :

```kotlin
val appsToUnsuspend = suspendedApps.toList()
suspendedApps.clear()
AppWatcherService.blockedApps.clear()
for (pkg in appsToUnsuspend) {
    ensureNotificationUnmuted(pkg)              // ← seulement les notifs
}
```

Il oubliait l'appel `DeviceOwnerHelper.unsuspendApp()`. À minuit, le compteur usage repassait à zéro et `suspendedApps` était vidé, mais l'OS gardait les apps `setPackagesSuspended(true)`. À partir de là, l'app était coincée — `checkAndUnblockApps()` n'avait plus rien à itérer, et seul un reflash ou un `pm unsuspend` ADB pouvait débloquer.

Fix : on appelle maintenant `DeviceOwnerHelper.unsuspendApp(this, pkg)` dans la boucle.

## Filet de sécurité au démarrage

Pour récupérer automatiquement les états déjà coincés (bug ci-dessus, ou simple kill du service pendant qu'une app était suspendue), `LimitService.onCreate()` appelle :

```kotlin
DeviceOwnerHelper.clearAllStuckSuspensions(this)
```

Cette méthode itère `PackageManager.getInstalledApplications()`, et pour chaque package marqué `isPackageSuspended()=true`, appelle `setPackagesSuspended(false)`. C'est sûr car :

- `setPackagesSuspended(false)` ne lève QUE les suspensions posées par notre admin (DPM est isolé par admin).
- Les suspensions système ou d'un autre DO (irréalisable ici, on est le seul DO) ne sont pas touchées.
- Le tick `enforceLimit()` qui suit (≤1s) re-suspend immédiatement toute app encore au-dessus du quota / en curfew → aucune brèche d'enforcement.

## Récupération manuelle

### Cas 1 : suspension posée par notre DO (`suspendingPackage=<0>android`)

L'API shell `pm unsuspend` ne fonctionne pas — les suspensions DPM sont isolées par admin, seul l'admin qui a posé la suspension peut la lever. Trois options :

**Broadcast `UNSUSPEND_ALL`** (recommandé, app doit tourner) :
```powershell
.\adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.UNSUSPEND_ALL
```
Déclenche `clearAllStuckSuspensions()` à la demande. Logue dans logcat (tag `SelfControl.DO`) le nombre de packages OK/failed et la liste — utile si on suspecte un échec silencieux. Note : `enforceLimit()` peut re-suspendre 1s plus tard ce qui est encore au-dessus du quota — comportement normal.

**Restart du service** : tuer le process déclenche `LimitService.onCreate()` qui appelle `clearAllStuckSuspensions()`. Mais l'auto-protection rend `am force-stop` souvent ineffective ; un reboot téléphone fait l'affaire.

**Re-injection du DO + relance** : si le DO a été retiré, le poser à nouveau (cf. `DEVICE_OWNER_PROVISIONING.md`) puis lancer SelfControl → libération automatique au démarrage.

### Cas 2 : suspension hors DO (`suspendingPackage=<0>root` ou autre)

Les suspensions posées via `pm suspend` (shell root) ou via un autre admin (Knox / Family Link) ne sont pas levables par notre DO. Identifier la source :
```powershell
.\adb shell "dumpsys package <pkg> | grep -A1 'Suspend params'"
```

Selon `suspendingPackage` :
- `<0>root` → `.\adb shell "su -c 'pm unsuspend <pkg>'"`
- `<0>android` → broadcast `UNSUSPEND_ALL` (cas 1)
- autre admin → désactiver l'admin source ou utiliser ses propres canaux

### Diagnostic rapide

Liste de toutes les apps suspendues via `dumpsys` (le flag `--suspended` n'existe pas sur toutes les versions ADB) :
```powershell
.\adb shell "for p in \$(pm list packages | sed 's/package://'); do dumpsys package \$p 2>/dev/null | grep -q 'suspended=true' && echo \$p; done"
```

Voir quels packages notre DO a dans sa liste :
```powershell
.\adb shell "dumpsys device_policy | grep suspendedPackages"
```

## Récap des invariants

| Évènement | Action sur `suspendedApps` | Action côté OS |
|---|---|---|
| Quota dépassé / curfew / jour interdit | `add(pkg)` | `setPackagesSuspended(pkg, true)` |
| Curfew terminé / app retirée du config | `remove(pkg)` | `setPackagesSuspended(pkg, false)` |
| Daily reset (minuit logique 02:00) | `clear()` | `setPackagesSuspended(pkg, false)` pour chacune ← **fixé** |
| Service onCreate (boot, restart) | (déjà vide) | `clearAllStuckSuspensions()` libère tout, puis re-suspend ce qui doit l'être au tick suivant |
| Nuclear Mode actif | non touché par daily reset | `hideApp(true)` (différent de suspend) |
