# provision_device_owner.ps1
param([switch]$DryRun, [switch]$Verbose)

$PKG = "com.jo.selfcontrol.ultimate"
$RECEIVER = "$PKG/com.jo.selfcontrol.ultimate.AdminReceiver"
$ADB = "adb"

Write-Host "=== Custos - Device Owner Provisioning (XML Injection) ===" -ForegroundColor Cyan

# 1. Vérifier ADB + appareil connecté
$devices = & $ADB devices
if ($devices -notmatch "device\s*$") {
    Write-Error "Aucun appareil connecté"
    exit 1
}

# 2. Vérifier root Magisk
$rootCheck = & $ADB shell "su -c id 2>/dev/null"
if ($rootCheck -notmatch "uid=0") {
    Write-Error "Root non disponible. Magisk requis."
    exit 1
}
Write-Host "✓ Root Magisk OK" -ForegroundColor Green

# 3. Vérifier app installée
$pkgInstalled = & $ADB shell "pm list packages $PKG"
if ($pkgInstalled -notmatch $PKG) {
    Write-Error "App $PKG non installée. Installe l'APK avec testOnly d'abord."
    exit 1
}
Write-Host "✓ App installée" -ForegroundColor Green

# 4. Injection XML
if ($DryRun) {
    Write-Host "[DryRun] Va injecter device_owner_2.xml via root." -ForegroundColor Yellow
} else {
    Write-Host "Génération du fichier XML Device Owner..." -ForegroundColor Cyan

    $xml = "<?xml version=`"1.0`" encoding=`"utf-8`" standalone=`"yes`" ?>`n<root>`n    <device-owner package=`"$PKG`" name=`"Custos`" component=`"$RECEIVER`" userUserId=`"0`" canAccessDeviceIds=`"true`" />`n</root>"
    Set-Content -Path "device_owner_2_tmp.xml" -Value $xml
    
    Write-Host "Poussée du fichier via ADB..." -ForegroundColor Cyan
    & $ADB push device_owner_2_tmp.xml /data/local/tmp/device_owner_2.xml | Out-Null
    
    Write-Host "Déplacement et application des permissions via root..." -ForegroundColor Cyan
    & $ADB shell "su -c 'cp /data/local/tmp/device_owner_2.xml /data/system/device_owner_2.xml && chown system:system /data/system/device_owner_2.xml && chmod 600 /data/system/device_owner_2.xml'"
    
    Remove-Item "device_owner_2_tmp.xml" -ErrorAction SilentlyContinue
    
    Write-Host "Device Owner injecté ! Un redémarrage est nécessaire pour que le système le prenne en compte." -ForegroundColor Yellow
    $confirm = Read-Host "Voulez-vous redémarrer le téléphone maintenant ? (oui/non)"
    if ($confirm -eq "oui") {
        & $ADB reboot
    }
}
