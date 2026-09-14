# ==============================================================================
# update_app.ps1 - Script de mise à jour automatique de Custos (SelfControl)
# ==============================================================================

$ErrorActionPreference = "Stop"

# 1. Détection d'ADB
$adb = if (Get-Command adb -ErrorAction SilentlyContinue) { 
    "adb" 
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") { 
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" 
} else { 
    Write-Host "[ERREUR] adb introuvable. Veuillez vérifier Android SDK ou ajouter adb au PATH." -ForegroundColor Red
    exit 1
}

# 2. Recherche de l'appareil Android
Write-Host "`n[1/5] Recherche de l'appareil Android connecté..." -ForegroundColor Cyan
$maxAttempts = 3
$deviceLines = @()

for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    $devicesOutput = & $adb devices
    $deviceLines = @($devicesOutput | Where-Object { $_ -match "\tdevice$" })
    if ($deviceLines.Count -gt 0) { break }
    Start-Sleep -Seconds 1
}

if ($deviceLines.Count -eq 0) {
    Write-Host "[ERREUR] Aucun appareil Android détecté avec ADB." -ForegroundColor Red
    Write-Host "Vérifiez que :" -ForegroundColor Yellow
    Write-Host "  1. Le téléphone est bien branché en USB." -ForegroundColor Yellow
    Write-Host "  2. Le Débogage USB est activé (Options de développement)." -ForegroundColor Yellow
    Write-Host "  3. Vous avez autorisé la connexion sur l'écran du téléphone." -ForegroundColor Yellow
    exit 1
}

$firstLine = [string]$deviceLines[0]
$deviceId = ($firstLine -split "`t")[0].Trim()
Write-Host "-> Appareil détecté : $deviceId" -ForegroundColor Green

# 3. Compilation de l'APK meDebug
Write-Host "`n[2/5] Compilation de l'application (assembleMeDebug)..." -ForegroundColor Cyan
& .\gradlew.bat assembleMeDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERREUR] Échec de la compilation Gradle." -ForegroundColor Red
    exit 1
}

$apkPath = "app\build\outputs\apk\me\debug\app-me-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "[ERREUR] Fichier APK introuvable à : $apkPath" -ForegroundColor Red
    exit 1
}
Write-Host "-> APK prêt : $apkPath" -ForegroundColor Green

# 4. Déverrouillage temporaire de l'installation (Device Owner)
Write-Host "`n[3/5] Déverrouillage temporaire des restrictions d'installation..." -ForegroundColor Cyan
& $adb -s $deviceId shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver
Start-Sleep -Seconds 2

# 5. Installation de la mise à jour
Write-Host "`n[4/5] Installation de l'APK sur l'appareil..." -ForegroundColor Cyan
& $adb -s $deviceId install -r -d -t $apkPath
$installOk = ($LASTEXITCODE -eq 0)

# 6. Re-verrouillage immédiat des restrictions d'installation
Write-Host "`n[5/5] Re-verrouillage des restrictions d'installation..." -ForegroundColor Cyan
& $adb -s $deviceId shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL -n com.jo.selfcontrol.ultimate/.CommandReceiver

if ($installOk) {
    Write-Host "`n[SUCCÈS] Mise à jour installée avec succès !" -ForegroundColor Green
    Write-Host "Relance de l'application..." -ForegroundColor Cyan
    & $adb -s $deviceId shell am start -n com.jo.selfcontrol.ultimate/.MainActivity
} else {
    Write-Host "`n[ERREUR] L'installation a échoué." -ForegroundColor Red
    exit 1
}
