# Journal d'événements (audit) — flavor `me` uniquement

Trace persistante et horodatée des décisions du moteur, pour diagnostiquer les « pouf, je suis sorti de l'app sans comprendre pourquoi ».

## ⚠️ Vie privée : `me` seulement

Un service d'accessibilité qui **persiste** l'historique des apps au premier plan est un problème de vie privée (et un motif de critique/rejet Play Store). Le journal est donc **désactivé par défaut** et **activé uniquement pour le flavor `me`** (la version développeur).

Mécanisme : `buildConfigField "boolean", "EVENT_LOG_ENABLED"` — `false` dans `defaultConfig`, `true` seulement dans le flavor `me` (`app/build.gradle`). `EventLog` court-circuite **toutes** ses méthodes si `!BuildConfig.EVENT_LOG_ENABLED`. Dans `basic` / `admin` / `deviceAdmin` : aucun écrit, aucun mirroir Logcat, rien. Le moteur *observe* toujours le premier plan pour bloquer, mais ne l'**enregistre** pas.

## Ce qui est tracé (catégories)

- `FG` — changement d'app au premier plan : `prev → new (class=…)`
- `SESSION` — `started` / `ended` avec la raison (`duration_timer`, `left_to_home`, `home reached`)
- `BLOCK` — blocage avec raison (`session_cooldown`, `quota`, `curfew`, `day`, `hour`, `session_daily`) + app au premier plan
- `UNBLOCK` — déblocage
- `HOME` — retour forcé à l'accueil (le « pouf »), avec motif + foreground

## Stockage & récupération

- Primaire : `<filesDir>/events.log` (fiable, survit reboot ; lecture en root).
- Ring buffer 512 KB (on jette la moitié la plus ancienne au dépassement).
- Mirroir Logcat : tag `SelfControl.Event` → `adb logcat -s SelfControl.Event`.

Commandes adb (flavor `me`, receiver `CommandReceiver`) :

```
# Copier le log vers un dossier pullable sans root
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.EXPORT_LOG
adb pull /sdcard/Android/data/com.jo.selfcontrol.ultimate/files/events.log

# Vider le log (repro propre)
adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.CLEAR_LOG
```

En root, lecture directe : `su -c 'cat /data/data/com.jo.selfcontrol.ultimate/files/events.log'`.

## Code

- `EventLog.kt` (dans `main/`, gardé par le flag) — logger + export + clear.
- Points d'instrumentation : `AppWatcherService` (FG, HOME), `SessionManager` (SESSION), `LimitService` (BLOCK, UNBLOCK, home-reached), `CommandReceiver` (EXPORT_LOG / CLEAR_LOG).
