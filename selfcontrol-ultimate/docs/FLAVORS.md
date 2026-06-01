# Product flavors — 4 niveaux d'anti-bypass

## Principe

L'**a11y service** (`AppWatcherService`) est la **base essentielle** de l'app : c'est lui qui détecte le foreground app, fait le HOME spam, et bloque l'accès aux pages Settings dangereuses. Tous les flavors l'embarquent. La feature session, les quotas journaliers, les curfews, etc. → toutes communes aux 4 versions.

**Ce qui change entre les flavors, c'est uniquement le mécanisme d'anti-désinstallation** :

| Flavor        | LEVEL | Public        | Anti-bypass différentiateur                                       | Vecteur de bypass restant                          |
|---------------|-------|---------------|--------------------------------------------------------------------|-----------------------------------------------------|
| `basic`       | 0     | user lambda   | aucun                                                              | reboot rapide → uninstall avant que l'a11y reload  |
| `admin`       | 1     | user motivé   | `AdminReceiver` enregistré comme legacy DeviceAdminReceiver        | Settings → Device admin apps → désactiver, puis uninstall |
| `deviceAdmin` | 2     | Mère (actuel) | + provisionné comme DPM Device Owner + Knox + `CommandReceiver`    | factory reset                                       |
| `me`          | 3     | moi           | identique deviceAdmin (futurs hooks root dans `src/me/java/`)      | flash recovery / unlock bootloader                  |

Même `applicationId` (`com.jo.selfcontrol.ultimate`) → un seul APK installé à la fois.

## Structure des sources

```
app/src/
├── main/                ← TOUT le moteur de blocage (18 .kt) + manifest universel
│   ├── java/.../        ← MainActivity, LimitService, AppWatcherService, SessionManager, …
│   ├── res/values/strings.xml
│   ├── res/xml/{accessibility_service_config,device_admin}.xml
│   └── AndroidManifest.xml  ← services, receivers et permissions universels
├── basic/               ← rien (juste .gitkeep)
├── admin/               ← AndroidManifest.xml fragment : + <receiver AdminReceiver>
├── deviceAdmin/         ← AndroidManifest.xml fragment : + AdminReceiver + CommandReceiver + Knox + testOnly
└── me/                  ← java/ vide pour l'instant (manifest = celui de deviceAdmin via gradle)
```

Configuration dans `app/build.gradle` :
```gradle
sourceSets {
    me.manifest.srcFile 'src/deviceAdmin/AndroidManifest.xml'
}
```
(c'est la seule ligne de magie source-set ; le reste suit la convention Android par défaut)

## Builder

```powershell
.\gradlew assembleBasicDebug
.\gradlew assembleAdminDebug
.\gradlew assembleDeviceAdminDebug
.\gradlew assembleMeDebug
```

APK dans `app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`. Tailles attendues : tous ~1607 KB, écarts < 1 KB (juste les receivers DPM en plus dans deviceAdmin/me).

## Gating runtime

`BuildConfig.LEVEL` (Int 0..3) et `BuildConfig.LEVEL_NAME` (String) sont injectés via `buildConfigField`. Utiliser pour les rares cas où du code dans `main/` doit savoir s'il tourne en mode privilégié :

```kotlin
if (BuildConfig.LEVEL >= 2) {
    // safe : on est deviceAdmin ou me, DPM dispo
    DeviceOwnerHelper.suspendApp(this, pkg)
}
```

À noter : `DeviceOwnerHelper` est dans `main/` (donc compile dans les 4 APK), mais ses méthodes vérifient `isDeviceOwner()` au runtime et no-op si pas DO. Pour `basic`/`admin` le HOME spam de l'a11y fait fallback.

## Pourquoi le moteur n'est PAS isolé par flavor

L'utilisateur a clarifié : l'a11y EST la base de l'app, présent dans tous les tiers. La différenciation par flavor concerne uniquement la couche anti-uninstall (legacy DeviceAdmin / DPM Device Owner / root). Mettre le moteur dans un dossier flavor-spécifique aurait obligé à dupliquer ou à abstraire artificiellement.

Le code DPM-only (`DeviceOwnerHelper`, `CommandReceiver`, `AdminReceiver`) **vit dans `main/` mais ne s'exécute jamais sur basic/admin** car aucun manifest entry ne le route. C'est ~10 KB de code "dormant" dans basic.apk, acceptable.

## Évolution future

- **`src/me/java/`** : zone réservée aux hooks root / Magisk / overlay LSPosed pour la version "me". Aucune dépendance avec les autres flavors.
- **`src/admin/java/`** ou **`src/deviceAdmin/java/`** : si on a besoin un jour de code Kotlin spécifique à un tier (rare), il y a la place.
- Si la duplication du bloc `<receiver AdminReceiver>` entre `admin/` et `deviceAdmin/` manifests devient gênante (rare), envisager un script Gradle qui génère les fragments à partir d'un template.
