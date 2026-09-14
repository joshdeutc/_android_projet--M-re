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

## 2. Autoriser l'installation d'autres applications (Play Store / APK)

Par défaut, l'installation d'applications est bloquée par le Device Owner (`DISALLOW_INSTALL_APPS`).

### Ouvrir une fenêtre temporaire d'installation (15 minutes par défaut) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

### Spécifier une durée personnalisée (en minutes, ex: 30 minutes) :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver --ei minutes 30
```

### Re-bloquer immédiatement les installations :
```powershell
& $adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver
```

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

## 4. Dépannage & Maintenance

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
