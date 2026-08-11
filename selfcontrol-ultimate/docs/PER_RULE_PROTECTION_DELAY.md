# Timer de protection par règle

Ajouté le 2026-07-31. Chaque règle peut porter son propre délai anti-triche, **prioritaire sur le délai global** de `DelayManager`.

## Pourquoi

Avant : un seul `global_delay_seconds` (plus des `delay_schedule` par plage horaire) gardait *tous* les assouplissements. Impossible de dire « ma curfew du soir, je ne veux pas pouvoir y toucher avant 30 jours, mais mon quota Instagram reste négociable en 1 h ». Il fallait choisir un délai unique, donc soit trop laxiste pour la curfew, soit insupportable pour le reste.

Après : `protection_delay_sec` sur n'importe quelle règle — limite d'app, curfew, groupe de blocage d'installation.

## Sémantique

Le champ est **optionnel**. Absent ou `<= 0` → la règle retombe sur le délai global (comportement d'avant).

- **Priorité, pas maximum.** Si une règle a un timer, il *remplace* le délai global pour cette règle, dans les deux sens. Un timer de 5 min l'emporte sur un délai global d'1 h.
- **Garde-fou indispensable** : baisser ou supprimer le timer d'une règle est lui-même un assouplissement, donc soumis au timer en vigueur (l'ancien). Sans ça la feature s'auto-désamorce — on mettrait 30 jours, puis on repasserait à 0 en un tap. C'est `isTimerLowered(old, new)` dans `ConfigManager`, qui traite `null` comme 0.
- **Le timer d'une règle survit au settings unlock.** `isSettingsUnlocked()` continue de court-circuiter le délai global, mais **pas** un timer explicite : sinon une curfew à 30 jours tomberait via un unlock à 1 h. Voir `unlockApplies` dans `MainActivity.saveConfigWithDelay`.
- **Plusieurs règles touchées dans une même sauvegarde → le plus strict gagne.** Le `limits.json` étant mis en file d'attente en bloc (un seul `PendingConfigUpdate` porte tout le fichier), toucher une curfew protégée 30 jours en même temps qu'un détail fait attendre 30 jours à l'ensemble. Pour éviter ça : sauvegarder les deux changements séparément.

## Ce qui compte comme assouplissement

`ConfigManager.requiredDefer(old, new, globalDelaySec)` renvoie un `DeferRequirement(seconds, fromExplicitTimer, reason)`. Il parcourt les règles de `old` (la protection en vigueur, pas celle demandée) :

| Type de règle | Clé d'appariement | Assouplissements détectés |
|---|---|---|
| `limits[]` | `package` | limite supprimée, `max_seconds_per_day` augmenté, session relâchée (durée ↑ / cooldown ↓ / sessions/jour ↑), timer réduit |
| `period_blocks[]` | minutes couvertes, puis `scheduleSignature()` | une minute bloquée qui ne l'est plus (union de *toutes* les nouvelles règles, donc scinder une règle sans perdre de couverture n'est pas un assouplissement), timer réduit |
| `install_blocks[]` | `name` | groupe supprimé, packages retirés du groupe, timer réduit |

`DeferRequirement.NONE` (0 s) → durcissement pur → écriture immédiate.

### Pourquoi `scheduleSignature()` et pas l'index

Les curfews sont éditées par index dans l'UI, mais l'index est instable dès qu'on en supprime une. Pour détecter la baisse de timer on apparie donc l'ancienne règle à la nouvelle par signature de contenu (packages + horaires + jours). Si la signature a disparu, c'est que les horaires ont changé — et le comparatif des minutes couvertes s'en charge déjà.

## Bug corrigé au passage

`MainActivity.saveConfigWithDelay` testait `delayState.globalDelaySeconds > 0` pour décider de mettre en attente, alors que `requestConfigUpdate` calculait l'échéance avec `getEffectiveDelaySeconds()` (= max du global et des `delay_schedule` actives). Conséquence : avec un global à 0 et une règle horaire de 30 min active, **tout assouplissement passait instantanément** — la règle horaire n'influençait que l'échéance d'un report qui n'avait jamais lieu. Le portillon utilise désormais le délai effectif.

## Format

```json
{
  "limits": [
    {
      "package": "com.instagram.android",
      "max_minutes_per_day": 30,
      "allowed_days": "*",
      "allowed_hours": "*",
      "protection_delay_sec": 3600
    }
  ],
  "period_blocks": [
    {
      "packages": ["com.tinder.android"],
      "blocked_hours": "22:00-07:00",
      "blocked_days": "*",
      "mute_notifications": true,
      "protection_delay_sec": 2592000
    }
  ]
}
```

## UI

Ligne « Protection timer: … » + bouton *Change* dans :

- l'éditeur de limite d'app (`showEditAppDialog`)
- l'éditeur d'horaires de curfew (`showCurfewHoursDialog`)
- chaque groupe de la section Install Blocklist (bouton *Timer*)

Presets plutôt qu'un `NumberPicker` : la plage utile va de l'heure au mois, ce qu'aucune molette ne rend confortablement. *Use global delay* / 1 h / 6 h / 24 h / 3 j / 7 j / 30 j / custom en heures (max 1 an).

Les règles protégées affichent un `🔒 Protected — Xj to change` orange dans les listes. `formatLongDuration()` existe parce que `formatTime()` rendrait 30 jours en `720h00m00s`.

## Pièges connus

- Le timer est stocké **dans `limits.json`**, donc il traverse le même mécanisme de mise en attente que le reste. Une baisse de timer mise en file d'attente reste visible dans l'UI (via `loadEditableConfig()` qui lit le pending) avant d'être appliquée — c'est volontaire, mais ça peut surprendre.
- Le calcul se fait toujours à partir de `old`. Si `old` vient lui-même d'un pending, on compare au pending, pas au fichier sur disque. Là encore volontaire : c'est l'état que l'utilisateur voit à l'écran.
