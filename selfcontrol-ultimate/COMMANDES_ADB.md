# Guide des commandes ADB — Custos (SelfControl Ultimate)

Ce fichier regroupe toutes les commandes ADB pour mettre à jour l'application, déverrouiller l'installation d'applications, et administrer les règles (notamment sur le flavor `me` où certaines actions sont réservées à ADB).

---

## 1. Mettre à jour l'application

### Méthode 1 : Automatique (Recommandée)
Double-cliquez sur `update_app.bat` ou lancez dans PowerShell :
```powershell
.\update_app.ps1
```
Ce script :
1. Détecte automatiquement ADB et votre téléphone branché en USB.
2. Compile la version `meDebug` (`.\gradlew assembleMeDebug`).
3. Ouvre la fenêtre temporaire d'installation Device Owner (`ALLOW_INSTALL`).
4. Installe l'APK mis à jour avec `-r -d -t`.
5. Re-verrouille immédiatement les installations (`BLOCK_INSTALL`).
6. Relance l'application sur le téléphone.

---

### Méthode 2 : Manuelle (Ligne par ligne)

Dans PowerShell :

```powershell
# Définir le chemin ADB
$adb = if (Get-Command adb -ErrorAction SilentlyContinue) { "adb" } else { "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }

# 1. Compiler l'APK
.\gradlew.bat assembleMeDebug

# 2. Déverrouiller l'installation (Device Owner)
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver

# 3. Installer la mise à jour
& $adb install -r -d -t app\build\outputs\apk\me\debug\app-me-debug.apk

# 4. Re-verrouiller l'installation
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver

# 5. Relancer l'application
& $adb shell am start -n com.jo.selfcontrol.ultimate/.MainActivity
```

---

## 2. Installation d'applications (Mode Whitelist Zero-Trust)

Avec l'architecture Whitelist Zero-Trust, l'installation d'applications est **débloquée en permanence**. Vous pouvez installer des applications depuis le Google Play Store ou via APK.
Dès qu'une application non-autorisée est installée, elle est **immédiatement masquée, suspendue et neutralisée** par Custos.
Pour l'utiliser, il faut faire une demande d'ajout à la Whitelist avec le délai de quarantaine de 24h (dans l'interface de l'app ou via ADB ci-dessous).

*(Les commandes `ALLOW_INSTALL` / `BLOCK_INSTALL` sont conservées pour la rétrocompatibilité mais ne sont plus nécessaires au quotidien).*

---

## 3. Gérer les règles de Partial Access (Accès Partiel)

Sur le build `me`, les règles d'accès partiel ne peuvent **pas être supprimées depuis le téléphone** pour éviter la tentation. Elles se gèrent via ADB.

### Voir la liste des règles actuelles :
```powershell
& $adb shell "run-as com.jo.selfcontrol.ultimate cat files/screen_rules.json"
```
Ou via broadcast dans logcat :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.LIST_SCREEN_RULES -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

### Supprimer une règle spécifique :
Remplacez `<NOM_DE_LA_REGLE>` par le nom exact (ex: `WhatsApp — blocked part`, `Snapchat — part 2`) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_SCREEN_RULE -n com.jo.selfcontrol.ultimate/.CommandReceiver --es name "NOM_DE_LA_REGLE"
```

*Exemple concret pour Snapchat :*
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_SCREEN_RULE -n com.jo.selfcontrol.ultimate/.CommandReceiver --es name "Snapchat — part 2"
```

---

## 4. Mode Whitelist Stricte (Zero-Trust)

Dans ce mode, toute application présente ou installée qui ne fait pas partie de la Whitelist est immédiatement masquée, suspendue et **désinstallée silencieusement par le Device Owner**.

### Voir le statut de la Whitelist et les demandes en attente :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.STATUS_WHITELIST -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

### Demander l'ajout d'une application (mise en quarantaine / délai incompressible) :
Vous pouvez indiquer **soit le nom de package**, **soit directement le lien Play Store complet** (l'id du package est automatiquement extrait) :
```powershell
# Avec le nom de package :
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REQUEST_WHITELIST_APP -n com.jo.selfcontrol.ultimate/.CommandReceiver --es pkg "com.nom.package"

# Ou avec le lien web Google Play Store complet :
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REQUEST_WHITELIST_APP -n com.jo.selfcontrol.ultimate/.CommandReceiver --es pkg "https://play.google.com/store/apps/details?id=com.nom.package"
```
Ou en spécifiant une durée personnalisée en heures (ex: 48 heures) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REQUEST_WHITELIST_APP -n com.jo.selfcontrol.ultimate/.CommandReceiver --es pkg "com.nom.package" --ei hours 48
```

### Configurer le délai de quarantaine Whitelist :
Augmenter le délai est immédiat ; réduire le délai est différé par le délai actuel (règle anti-impulsion) :
```powershell
# Définir un délai dédié en heures (ex: 48h) :
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.SET_WHITELIST_DELAY -n com.jo.selfcontrol.ultimate/.CommandReceiver --ei hours 48

# Aligner le délai de quarantaine sur le délai général (Delay & Limits) :
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.SET_WHITELIST_DELAY -n com.jo.selfcontrol.ultimate/.CommandReceiver --ez global true
```

### Annuler une demande en attente (action de durcissement, immédiate) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.CANCEL_WHITELIST_APP -n com.jo.selfcontrol.ultimate/.CommandReceiver --es pkg "com.nom.package"
```

### Retirer une application de la Whitelist (action de durcissement immédiate) :
L'application est immédiatement verrouillée, masquée et suspendue :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_WHITELIST_APP -n com.jo.selfcontrol.ultimate/.CommandReceiver --es pkg "com.nom.package"
```

### Forcer un balayage immédiat de vérification :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.ENFORCE_WHITELIST -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

---

## 5. Dépannage & Maintenance

### Débloquer toutes les applications suspendues (si une app reste grisée/bloquée à tort) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.UNSUSPEND_ALL -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

### Vérifier le statut de l'application et du Device Owner :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.STATUS -n com.jo.selfcontrol.ultimate/.CommandReceiver
& $adb shell dpm list-owners
```

### Voir les logs récents de SelfControl :
```powershell
& $adb shell "run-as com.jo.selfcontrol.ultimate tail -n 30 files/events.log"
```
