# Daily reset bug — root cause + fix (session 2026-05-19)

## Symptôme observé

User a utilisé Chyrpe > 10min hier soir → blocage normal (icône grisée par DPM).
Au réveil à 8h : icône ungrayed quelques secondes, puis ouverture → HOME redirect →
re-grisée. Settings montrait `usageToday[chyrpe] >= 600s` au lieu de 0.

Touche TOUS les flavors (basic/admin/deviceAdmin/me) — le bug est dans `LimitService`
(dans `main/`), pas dans la couche anti-uninstall flavor-spécifique.

## Cause racine (3 bugs combinés)

1. **`usageToday` + `suspendedApps` étaient en-mémoire seulement.** Service tué pendant
   la nuit (Doze Samsung) → état perdu. La suspension DPM, elle, persiste OS-side.

2. **`clearAllStuckSuspensions()` au boot dé-suspendait tout sans distinction.** Au
   restart du service, il levait la suspension DPM de Chyrpe alors qu'elle devait
   rester. D'où l'icône ungrayed quelques secondes.

3. **`queryAndAggregateUsageStats(today 2h, now)` renvoyait l'usage d'hier** sur
   Samsung A53/Android 15 (quirk de bucket aggregation). Au moment où l'enforce tick
   suivant tournait, il re-pullait 600+ → blockApp → re-suspend → re-gris.

Plus : le reset à 2h dépend du Handler `postDelayed(1s)` qui dort en Doze. Pas de
fallback alarm.

## Fix

| Fichier nouveau / modifié | Rôle |
|---|---|
| `UsageStateStore.kt` (new) | Persiste `usageToday` + `suspendedApps` + `logicalDayStartMs` dans `filesDir/usage_state.json` |
| `UsageQuery.kt` (new) | Remplace `queryAndAggregateUsageStats` par `queryEvents` → somme exacte des fenêtres `ACTIVITY_RESUMED`→`ACTIVITY_PAUSED/STOPPED` dans `[beginMs, endMs)` |
| `DailyResetReceiver.kt` (new) | `AlarmManager.setExactAndAllowWhileIdle` à 02:00:30, re-scheduled à chaque fire et au boot. Garantit le wake-up même en Doze. Fallback `setAndAllowWhileIdle` si `SCHEDULE_EXACT_ALARM` indisponible. |
| `DeviceOwnerHelper.clearAllStuckSuspensions(ctx, keep)` | Nouveau param `keep: Set<String>` — n'unsuspende pas les apps du set. `LimitService` lui passe `suspendedApps` restauré du disque. |
| `LimitService.onCreate` | Charge l'état persisté avant `clearAllStuckSuspensions`. Si `logicalDayStartMs` match aujourd'hui → restore verbatim + skip `loadTodayUsageFromSystem` (qui héritait du bug C). Sinon : fresh day. |
| `LimitService.enforceLimit` rollover | Skip `loadTodayUsageFromSystem` aussi au rollover — clear et repart à 0. Persist après. |
| `LimitService.blockApp / checkAndUnblockApps / checkConfigReload` | `persistState()` immédiat après changement de `suspendedApps`. |
| `LimitService` tick usage++ | `persistStateThrottled()` (5s). |
| `AndroidManifest.xml` | `<receiver android:name=".DailyResetReceiver" />` |
| `BootReceiver` | `DailyResetReceiver.schedule(context)` après reboot. |

## Garanties après fix

- Service tué mid-day → restart → `usageToday` et `suspendedApps` restaurés. Les apps
  over-quota restent grisées (re-`suspendApp` après `clearAllStuckSuspensions(keep=...)`).
- Rollover à 2h garanti par AlarmManager même en Doze profond.
- `queryEvents` ne peut plus retourner d'usage d'hier dans la fenêtre `[today 2h, now]`.
- Au true cold-start (pas de persisted state), `loadTodayUsageFromSystem` est encore
  appelée mais via `UsageQuery` qui est fiable.

## Vérif build

`./gradlew assembleBasicDebug assembleAdminDebug assembleDeviceAdminDebug assembleMeDebug`
→ BUILD SUCCESSFUL, 4 APK générés.

## À tester manuellement

1. Use Chyrpe > limit → block.
2. `adb shell am force-stop com.jo.selfcontrol.ultimate` (ou attendre Doze).
3. Attendre 5min, relancer (tap MainActivity ou wait for watchdog).
4. Vérifier : Chyrpe toujours grisée, `usageToday[chyrpe] >= limit` dans dashboard.
5. Passer minuit + 2h (changer horloge système si besoin).
6. Vérifier : Chyrpe ungrayed à 2h précisément, `usageToday[chyrpe] = 0`.

## Bug bonus découvert pendant le déploiement : UsageQuery comptait 6h19m au lieu de 5s

### Symptôme

Après install du fix + reset, le dashboard affichait pour Chyrpe `6h19m02s` alors que
l'usage réel d'aujourd'hui était de 5 secondes (vérifié via `dumpsys usagestats`).

### Cause

Mon premier jet de `UsageQuery.foregroundSecondsByPackage` traitait
`ACTIVITY_PAUSED` ET `ACTIVITY_STOPPED` comme des "close session" events :

```kotlin
UsageEvents.Event.ACTIVITY_PAUSED,
UsageEvents.Event.ACTIVITY_STOPPED -> {
    val start = openSince.remove(pkg) ?: beginMs  // ← le piège
    val end = minOf(ev.timeStamp, endMs)
    if (end > start) totals[pkg] += (end - start)
}
```

Pour chaque activity Android, l'ordre des events est `RESUMED → PAUSED → STOPPED`.
La séquence en pratique pour Chyrpe ce matin :

```
08:18:52  ACTIVITY_RESUMED  com.chyrpe.chyrpe
08:18:56  ACTIVITY_PAUSED   com.chyrpe.chyrpe   ← close session: +4s, retire de openSince
08:18:57  ACTIVITY_STOPPED  com.chyrpe.chyrpe   ← openSince vide → fallback beginMs
                                                  → +(08:18:57 - 02:00:00) = +6h18m57s
```

Le `?: beginMs` est légitime quand `RESUMED` a fired AVANT la fenêtre de query
(session straddling, `queryEvents` n'a pas retourné le RESUMED car hors fenêtre).
Mais combiné avec STOPPED qui fire toujours après PAUSED, il devient un compteur
fantôme massif qui ajoute "begin → STOPPED" à chaque close d'activity.

### Fix

Ne handler QUE `ACTIVITY_PAUSED`. Le STOPPED est ignoré. Le fallback `?: beginMs`
reste correct pour le seul cas légitime (session qui chevauche `beginMs`).

```kotlin
UsageEvents.Event.ACTIVITY_PAUSED -> {
    val start = openSince.remove(pkg) ?: beginMs
    ...
}
```

### Leçon

`UsageEvents` a plusieurs events par lifecycle transition et ils sont **pas
mutuellement exclusifs**. Si tu sommes des durées via openSince/close, traite UN
seul event comme close, pas deux. Sinon le second close va trigger ton fallback de
session-straddling et compter de zéro/beginMs à chaque fois.

Vérifie toujours ton compteur contre `dumpsys usagestats` pour confirmer un ordre
de grandeur réaliste avant de croire les chiffres affichés.
