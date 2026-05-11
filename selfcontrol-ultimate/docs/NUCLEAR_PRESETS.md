# Nuclear Mode — Presets nommés

Ajouté le 2026-05-11. Permet à l'utilisateur de sauvegarder plusieurs configurations Nuclear (liste d'apps + durée) sous un nom, et de les ré-appliquer rapidement.

## Pourquoi

Avant : un seul "preset" implicite était stocké (`lastNuclearApps`, `lastNuclearDurationMin`), écrasé à chaque nouvelle activation. L'utilisateur perdait sa config dès qu'il essayait une variante.

Après : N presets nommés, plus le slot "Configure new…" qui repart de zéro.

## Composants

- **`NuclearPresetsManager.kt`** — persistance JSON dans `filesDir/nuclear_presets.json`. API :
  - `list()` / `get(name)` / `save(name, apps, durationMin)` / `delete(name)` / `rename(old, new)`
  - Un preset = `{ name: String, apps: List<String>, durationMinutes: Int }`
- **`MainActivity.kt`** :
  - Bouton 💾 *Save as preset* dans le dialog de confirmation Nuclear → prompt nom → sauvegarde, dialog reste ouvert
  - Picker au tap "Activate Nuclear Mode" : liste des presets + "Configure new…" en tête
  - Bouton *Manage Presets* sous le bouton principal → renommer / supprimer

## État persisté

- `nuclear_presets.json` dans `filesDir` (survit aux updates `installDebug`, perdu seulement au clear-data ou factory-reset)
- Anciens `lastNuclearApps` / `lastNuclearDurationMin` toujours là pour pré-remplir le mode "Configure new…" — non migrés en preset par défaut (volontaire, pour éviter un preset "Unnamed" parasite).

## Pièges connus

- Installer une nouvelle version pendant que Device Owner est actif → `DISALLOW_INSTALL_APPS` bloque `adb install`. Workflow :
  1. `adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -p com.jo.selfcontrol.ultimate`
  2. `./gradlew installDebug`
  3. `adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL -p com.jo.selfcontrol.ultimate`
  Le `-p <DPC>` est obligatoire sur Android 15 (cf. `TROUBLESHOOTING_ANDROID_15_INSTALL.md`).
