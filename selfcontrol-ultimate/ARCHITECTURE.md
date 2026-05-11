# SelfControl Ultimate - Architecture

Cette édition (Device Owner Edition) utilise principalement le système natif d'Android pour assurer un blocage inébranlable, soutenu par un renfort en espace utilisateur. Le module Magisk a été officiellement supprimé pour éviter des problèmes d'instabilité système et de conflits ADB.

L'architecture est structurée en 2 couches de sécurité :

## 1. Couche Device Owner (Niveau Système OS)
Puisque l'application est configurée comme Device Owner (activé via l'injection du fichier XML root) :
- **Désinstallation bloquée** via `setUninstallBlocked(self)`. L'interface système grise le bouton de désinstallation.
- **Autorisation automatique** des accès sensibles, tels que `USAGE_STATS` (pour analyser l'historique d'utilisation des applications).
- **Auto-réactivation de l'Accessibilité** si elle est coupée, via la réécriture forcée du paramètre sécurisé `ENABLED_ACCESSIBILITY_SERVICES`. *Ne fonctionne plus sur Android 15 (cf. `docs/DEVICE_OWNER_PROVISIONING.md` — la couche A11Y se protège elle-même via interception des events Settings).*
- **Suspension système des applications** : gèle complètement l'accès et grise les icônes des apps restreintes via `setPackagesSuspended()`. Cycle de vie détaillé dans `docs/APP_SUSPENSION_LIFECYCLE.md`.
- **Interdiction formelle des installations** : bloque le Play Store et les APK téléchargés via `DISALLOW_INSTALL_APPS` et `DISALLOW_INSTALL_UNKNOWN_SOURCES`. Seul un ordinateur via ADB avec une action précise peut installer une autre application.
- **Restrictions complémentaires** : `DISALLOW_FACTORY_RESET` et `DISALLOW_SAFE_BOOT` (le mode safe désactiverait l'A11Y).

## 2. Couche AccessibilityService (Espace Utilisateur)
Tournant en arrière-plan, le service d'accès (`AppWatcherService`) soutient l'appli :
- **Redirection brutale (HOME spam)** : s'assure qu'une application qui passerait à travers la suspension du système subit un renvoi immédiat à l'écran d'accueil.
- **Protection des paramètres** : intercepte les accès aux menus sensibles (paramètres avancés, page accessibilité) pour bloquer toute tentative de modification ou d'arrêt des processus liés à l'application. Compat OneUI 5 / OneUI 7 documentée dans `docs/APPWATCHER_SAMSUNG_COMPAT.md`.

## Modes d'enforcement supportés

`LimitService` combine plusieurs règles, évaluées chaque seconde :

- **Quota quotidien** (`maxSecondsPerDay`) — usage cumulé via `UsageStatsManager`, reset à 02h00 (jour logique).
- **Curfew / Period blocks** — plages horaires interdites, indépendantes du quota. Peut couper aussi les notifications (`SelfControlNotificationListener`).
- **Allowed days / hours** — restrictions par jour de semaine et plage horaire.
- **Nuclear Mode** — blocage temporaire renforcé d'un set d'apps. État persisté via `NuclearManager`. Utilise `setApplicationHidden()` en plus de la suspension. Presets nommés (apps + durée) gérés par `NuclearPresetsManager` — voir `docs/NUCLEAR_PRESETS.md`. **Note :** l'override DND a été retiré (2026-05-11) pour respecter les exceptions Android définies par l'utilisateur ; le blocage des notifs reste assuré par `SelfControlNotificationListener` quand actif.

## Robustesse au redémarrage

`suspendedApps` est en mémoire seulement, mais `setPackagesSuspended()` persiste côté OS. Pour éviter qu'une app reste coincée suspendue après un kill du service ou un reboot, `LimitService.onCreate()` appelle `DeviceOwnerHelper.clearAllStuckSuspensions()` qui lève toutes les suspensions posées par notre admin. Le tick d'enforcement suivant (≤1s plus tard) re-suspend immédiatement les apps qui doivent encore l'être. Voir `docs/APP_SUSPENSION_LIFECYCLE.md`.

## Package Final
`com.jo.selfcontrol.ultimate`
