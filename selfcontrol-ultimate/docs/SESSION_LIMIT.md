# Session limit (Discord-style, per-unlock)

Inspiré de la limite Discord de `notubeplayer`. Priorité plus basse que la curfew et le quota journalier : si l'une des deux bloque, la session n'est pas évaluée.

## Sémantique

- **Ouvrir l'app** (devient foreground) alors qu'aucune session n'est active ET hors cooldown ET `sessionsUsed < maxSessionsPerDay` → démarre une session : `sessionStartMs = now`, `sessionsUsed += 1`.
- **Revenir à l'accueil** (le foreground devient le launcher) → session terminée *immédiatement*, le cooldown démarre depuis l'instant de sortie. ⚠️ C'est le **seul** déclencheur de fin par sortie : passer brièvement à autre chose *à l'intérieur* du flux de l'app (dialogue, webview/custom-tab, share sheet, clavier, system UI, swap de fenêtre pendant l'envoi d'un message) ne ferme **pas** la session. Basculer directement vers une autre app sans passer par l'accueil laisse la session active — c'est alors le timer `session_duration_sec` qui l'expire tout seul.
- **Atteindre `sessionDurationSec`** sans avoir quitté → session terminée, cooldown depuis la fin de session.
- **En cooldown** ou **`sessionsUsed >= max`** → app bloquée (`reason="session_cooldown"` ou `"session_daily"`).
- **Rollover journalier** (jour logique = 2 h du matin) → `sessionsUsed = 0`, cooldown reset.

⚠️ **Revenir à l'accueil = consommer une session entière.** Si tu ouvres l'app, restes 10 s puis reviens à l'accueil, tu as utilisé 1 session sur N et démarré ton cooldown. C'est volontaire (spec utilisateur).

### Pourquoi « accueil » et pas « tout changement de foreground »

Historiquement la session se fermait dès que `currentForegroundApp` n'était plus le package cible. Problème : pendant le flux interne de l'app (envoi d'un message sur Chyrpe par ex.) une fenêtre intermédiaire — dialogue, webview/custom-tab, share sheet, clavier, system UI, swap de fenêtre pendant l'envoi — devient brièvement foreground et tuait la session instantanément. La fin par sortie est donc déclenchée **uniquement** quand le foreground devient le launcher. `LimitService.endSessionsForLeftPackages` ne ferme les sessions que si `currentApp ∈ launcherPackages()` (résolu via `ACTION_MAIN`/`CATEGORY_HOME`, mis en cache).

## Configuration

Optionnel par package dans `limits.json`. Format :

```json
{
  "limits": [
    {
      "package": "com.discord",
      "max_minutes_per_day": 30,
      "allowed_days": "*",
      "allowed_hours": "*",
      "session": {
        "session_duration_sec": 300,
        "cooldown_sec": 14400,
        "max_sessions_per_day": 3
      }
    }
  ]
}
```

- `session_duration_sec` : durée max d'une session (notubeplayer = 300 = 5 min)
- `cooldown_sec` : attente après fin de session (notubeplayer = 14400 = 4 h)
- `max_sessions_per_day` : nombre de sessions autorisées avant blocage jusqu'au lendemain (notubeplayer = 3)

Sans bloc `session` → la limite est désactivée pour ce package (comportement identique à avant la feature).

## État persisté

Fichier `<filesDir>/session_state.json`. Survit reboot / kill / réinstall (jusqu'au prochain factory reset / clear data).

```json
{
  "tracking_day": 138,
  "packages": {
    "com.discord": {
      "session_start_ms": 1715000000000,
      "cooldown_end_ms": 0,
      "sessions_used": 1
    }
  }
}
```

`session_start_ms = 0` → pas de session active. `cooldown_end_ms = 0` → pas de cooldown.

## Intégration LimitService

Dans `enforceLimit()` (toutes les 1 s) :

1. Day rollover → `SessionManager.resetDayIfNeeded()`
2. `endSessionsForLeftPackages(currentApp, now)` — **uniquement si `currentApp` est le launcher** : toute session active est close → cooldown
3. Curfew check (si bloque → return)
4. Day/hour/quota check (si bloque → return)
5. **`SessionManager.evaluate(currentApp, session, now)`** — démarre ou continue/bloque la session

`shouldStayBlockedForNonCurfewReasons()` consulte aussi `SessionManager.shouldStayBlocked()` pour que `checkAndUnblockApps()` ne fasse pas yo-yo entre suspend/unsuspend pendant un cooldown.

## TODO (pas fait ici)

- UI dans `MainActivity` pour configurer le bloc `session` par app (actuellement il faut éditer `limits.json` à la main)
- Notification "il vous reste X sec dans la session" pendant qu'une session est active
- Tests d'intégration via `TestAutomationReceiver` (broadcasts pour simuler open/leave)
