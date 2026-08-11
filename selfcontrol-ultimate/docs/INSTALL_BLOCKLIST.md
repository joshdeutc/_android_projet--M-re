# Blocage d'installation par package

Ajouté le 2026-07-31. Groupes nommés de packages qui ne doivent jamais tourner sur l'appareil, avec import CSV.

## Ce qu'Android permet — et ce qu'il ne permet pas

**Aucune API DPM ne prend un nom de package et empêche son installation future.** C'est le point de départ de la conception, il faut l'avoir en tête avant de toucher à ce code.

| API | Réalité |
|---|---|
| `DISALLOW_INSTALL_APPS` / `..._UNKNOWN_SOURCES` | Tout ou rien. Déjà posées (cf. `ADB_ONLY_INSTALLS.md`), aucune granularité par package. |
| `setUninstallBlocked(pkg)` | Bloque la **dés**installation. L'inverse du besoin. |
| `setPackagesSuspended` / `setApplicationHidden` | Exigent que le package soit **déjà installé**. |
| Allowlist Managed Google Play | La vraie fonction « seules ces apps sont installables », mais elle exige un enrôlement Android Enterprise (serveur EMM + compte Google Play managé). Un Device Owner provisionné localement ne l'a pas. |

## Le choix : kill switch post-installation

Faute d'API préventive, l'enforcement est **réactif** : dès qu'un package de la blacklist apparaît, il est masqué via `setApplicationHidden()`. Il disparaît du launcher et devient non lançable.

**Masquer plutôt que désinstaller**, délibérément :

- réversible, et sans perte de données utilisateur ;
- fonctionne sur les apps **préinstallées du ROM** — le navigateur d'usine ne peut pas être désinstallé du tout, et c'est justement le cas d'usage principal ;
- couvre donc aussi les apps **déjà présentes** au moment où on crée le groupe, pas seulement les installations futures.

Trois points d'accroche, tous dans `LimitService` :

1. **`onCreate()`** → `InstallBlockManager.enforce()`. Rattrape ce qui a été installé pendant que le service était mort, et libère les groupes dont le timer a expiré.
2. **Receiver `ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED`**, enregistré à l'exécution → masquage en quelques millisecondes. Enregistré à l'exécution et pas dans le manifest : les broadcasts implicites déclarés en manifest sont bloqués depuis Android 8, `PACKAGE_ADDED` n'atteindrait jamais l'app.
3. **`checkConfigReload()`** → re-enforce. C'est le chemin qu'emprunte une suppression de groupe quand `DelayManager` finit par l'appliquer.

Si `hideApp()` est refusé (certains packages système), fallback sur `suspendApp()`. Dans tous les cas le package est ajouté à `AppWatcherService.blockedApps`, donc la couche A11Y le renvoie à l'accueil s'il parvient malgré tout à se lancer.

## Interaction avec le sweep de démarrage

`LimitService.onCreate()` charge désormais la config **avant** `clearAllStuckSuspensions()`, pour pouvoir passer les packages install-bloqués dans `keep`. Sans ça, le sweep lèverait la suspension de ceux qu'on tient via le fallback (quand `hideApp` a échoué), et ils resteraient utilisables jusqu'au tick suivant.

## État persisté

`<filesDir>/install_block_state.json` :

```json
{ "hidden": ["com.android.chrome", "org.mozilla.firefox"] }
```

Nécessaire parce que l'état `setApplicationHidden` vit dans l'OS **sans attribution** : sans ce fichier on ne saurait ni distinguer nos packages de ceux masqués par autre chose, ni quoi démasquer quand le timer d'un groupe expire enfin.

## Garde-fous

`InstallBlockManager.HARD_GUARDS` refuse de masquer, quoi que dise la config : `android`, `com.android.systemui`, `com.android.settings`, `com.google.android.gms`, `com.android.permissioncontroller`, notre propre package, et **les launchers résolus dynamiquement** (`ACTION_MAIN`/`CATEGORY_HOME`). Masquer l'un de ces packages briquerait l'appareil ou désactiverait l'app qui applique les règles. Les refus sont loggés.

## Configuration

Les groupes vivent dans `limits.json`, sous `install_blocks` — **pas** dans un fichier séparé. C'est délibéré : le mécanisme de mise en attente de `DelayManager` écrit `limits.json` en bloc, donc y loger les groupes fait qu'une suppression passe par le portillon de délai sans aucun code supplémentaire.

```json
{
  "limits": [],
  "period_blocks": [],
  "install_blocks": [
    {
      "name": "Navigateurs",
      "packages": ["com.android.chrome", "org.mozilla.firefox", "com.opera.browser"],
      "protection_delay_sec": 2592000
    }
  ]
}
```

`protection_delay_sec` est le timer par règle décrit dans `PER_RULE_PROTECTION_DELAY.md`.

## Asymétrie ajout / retrait

- **Ajouter** un package ou un groupe = durcissement → **immédiat**, aucun délai.
- **Retirer** un package, supprimer un groupe, baisser son timer = assouplissement → passe par le délai (celui du groupe s'il en a un, sinon le global).

C'est ce qui rend l'import CSV sûr : le merge est **add-only**, donc un import ne peut jamais relâcher quoi que ce soit et s'applique instantanément. `mergeIntoConfig()` ne supprime rien et ne touche à aucun timer.

## Format CSV

Deux colonnes, `group` et `package`. En-tête optionnel — s'il est présent, l'ordre des colonnes est respecté (donc `package,group` marche aussi). Sans en-tête, l'ordre supposé est `group,package`.

```csv
group,package
Navigateurs,com.android.chrome
Navigateurs,org.mozilla.firefox
Navigateurs,com.opera.browser
Navigateurs,com.microsoft.emmx
Stores,com.android.vending
```

Tolérances du parseur : BOM UTF-8 (Excel et Notepad en ajoutent un), CRLF, lignes vides, commentaires `#`, cellules entre guillemets contenant des virgules. Une ligne dont la colonne package ne ressemble pas à un nom de package est **rejetée individuellement** et rapportée, sans faire échouer l'import entier.

## Deux points d'entrée pour l'import

**Depuis l'app** — bouton *Import CSV* → `ACTION_OPEN_DOCUMENT`. Le type MIME demandé est `*/*` volontairement : les providers annoncent un CSV en `text/csv`, `text/comma-separated-values`, `text/plain` ou `application/octet-stream` selon la source, filtrer masquerait des fichiers réels. Un dialog de prévisualisation montre les groupes, les compteurs et les lignes rejetées avant d'écrire.

**Depuis le PC** — flavors `deviceAdmin` / `me` uniquement :

```powershell
$D = "/sdcard/Android/data/com.jo.selfcontrol.ultimate/files"
adb push blocklist.csv "$D/blocklist.csv"
adb shell am broadcast -p com.jo.selfcontrol.ultimate `
  -a com.jo.selfcontrol.ultimate.IMPORT_INSTALL_BLOCKS --es path "$D/blocklist.csv"
```

Le `-p <DPC>` est obligatoire sur Android 15 (cf. `TROUBLESHOOTING_ANDROID_15_INSTALL.md`).

⚠️ **Le chemin doit être notre dossier externe, pas `/sdcard` directement.** Le stockage cloisonné (Android 11+) refuse `/sdcard/blocklist.csv` à l'app avec `EACCES` — elle ne détient aucune permission de stockage, et lui en donner une pour ça serait disproportionné. `adb push` peut en revanche écrire dans `Android/data/<pkg>/files`, que l'app lit sans permission : c'est le seul chemin qui satisfait les deux côtés. Vérifié sur Pixel 8a le 2026-08-03 — la première tentative sur `/sdcard` a échoué exactement ainsi.

## UI

Section *Install Blocklist*, entre Curfew et Nuclear Mode. Par groupe : nom (tapable → liste complète des packages, avec 🚫 pour ceux effectivement neutralisés), compteurs, timer, et trois boutons *Apps* / *Timer* / *Delete*.

**Piège de l'éditeur *Apps*** : le picker ne liste que les apps *installées et lançables*. Les packages venus d'un CSV et non installés — l'essentiel d'une blacklist — n'y apparaissent pas et seraient perdus à la sauvegarde. `showEditInstallGroupAppsDialog` les reporte donc explicitement et les réinjecte intacts (`selected + invisible`). Pour les éditer, il faut passer par le CSV.

## Liste prête à l'emploi

`browsers_blocklist.csv` à la racine du projet : ~44 packages de navigateurs (Chrome et ses canaux, Samsung Internet, Firefox/Fenix/Focus, Edge, Brave, Opera, DuckDuckGo, Vivaldi, Kiwi, Yandex, UC, les navigateurs OEM Xiaomi/Huawei/Oppo/Vivo, Tor…). Les packages non installés restent dans la liste sans nuire : ils seront masqués s'ils apparaissent un jour.

## Pièges connus

- **Un package masqué est invisible à `getApplicationInfo()`, y compris pour nous.** C'est le piège central de cette feature, et il s'est vérifié en vrai. `isInstalled()` doit passer `MATCH_UNINSTALLED_PACKAGES`, sinon un package qu'on vient de masquer paraît désinstallé au balayage suivant, `enforce()` le croit retiré de la config et le **relâche** : la blocklist se désamorçait toute seule au premier rechargement de config ou reboot. Deux verrous désormais : le flag de matching, et surtout le fait que le relâchement est piloté par l'intention de la config (`previouslyHidden - targets`) et jamais par l'absence apparente d'un package. Corrigé et vérifié le 2026-08-03 : premier `enforce()` masque et journalise, second ne relâche rien.
- **`MATCH_UNINSTALLED_PACKAGES` fait aussi correspondre les stubs système.** Sur Pixel, `com.android.chrome` « désinstallé » reste présent en stub : il est donc compté comme cible, `hideApp()` le refuse, et le fallback suspension prend le relais. C'est le comportement voulu — le package est neutralisé d'avance s'il revient — mais ça explique un compteur « 1 currently neutralised » alors qu'aucun navigateur n'est visiblement installé.

- **Poser le timer après l'import.** Un groupe créé par CSV naît sans `protection_delay_sec`, donc protégé par le seul délai global. L'import étant un durcissement, il est instantané ; c'est le bouton *Timer* qui verrouille ensuite le groupe.
- **App à la fois install-bloquée et sous quota** (config contradictoire, mais possible) : quand `checkAndUnblockApps()` débloque le quota, il retire le package de `AppWatcherService.blockedApps`. Le masquage OS tient toujours — c'est le mécanisme principal — mais la 3e couche A11Y est perdue jusqu'au prochain `enforce()` (démarrage du service ou rechargement de config). Non corrigé : la config n'a pas de sens, et le masquage suffit.
- **Le re-enforce n'est pas dans le tick d'1 s.** Vérifier l'état de chaque package bloqué chaque seconde coûterait un appel PackageManager par package et par seconde pour rien. Les trois points d'accroche (démarrage, `PACKAGE_ADDED`, rechargement de config) couvrent tous les cas réels.

## Ce que cette couche ne fait pas

Elle **ne bloque pas la page Play Store** d'une app blacklistée. Une couche A11Y de retour arrière avait été envisagée, mais la page de détail du Play Store n'expose pas le package cible dans l'arbre d'accessibilité : le match ne pourrait se faire que sur le titre affiché, avec des faux positifs sur les noms courts. Décision : couche A uniquement, match exact sur le package. La conséquence est qu'on peut encore *voir* la fiche d'une app bloquée, simplement pas s'en servir.
