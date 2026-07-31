# remove_device_owner.ps1
# Emergency Backdoor Script
param([switch]$DryRun, [switch]$Verbose)

$PKG = "com.jo.selfcontrol.ultimate"
$ADB = "adb"

Write-Host "=== Custos - Device Owner Removal Backdoor ===" -ForegroundColor Red

if ($DryRun) {
    Write-Host "[DryRun] Commande à exécuter :" -ForegroundColor Yellow
    Write-Host "    su -c 'am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_OWNER -n com.jo.selfcontrol.ultimate/.CommandReceiver --es password `"selfcontrol2026`"'"
} else {
    Write-Host "Suppression du Device Owner..." -ForegroundColor Cyan
    $result = & $ADB shell "su -c 'am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_OWNER -n com.jo.selfcontrol.ultimate/.CommandReceiver --es password `"selfcontrol2026`"'" 2>&1
    Write-Host $result

    Write-Host "Vérification :" -ForegroundColor Yellow
    & $ADB shell "dumpsys device_policy | grep device-owner"
    Write-Host "Si vide, le Device Owner a bien été supprimé." -ForegroundColor Green
    Write-Host "L'application peut maintenant être désinstallée normalement depuis les paramètres Android."
}