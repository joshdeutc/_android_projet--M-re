# install_xapk.ps1
# Installe une .xapk via ADB en gérant les restrictions Device Owner posées
# par SelfControl Ultimate (DISALLOW_INSTALL_APPS / DISALLOW_INSTALL_UNKNOWN_SOURCES).
#
# Workflow :
#   1. Broadcast ALLOW_INSTALL  -> lève les restrictions (idempotent)
#   2. Décompresse le .xapk     -> base.apk + splits + éventuel OBB
#   3. adb install-multiple     -> installe tout en une transaction
#   4. Push OBB                 -> /sdcard/Android/obb/<pkg>/ si présents
#   5. Broadcast BLOCK_INSTALL  -> rétablit les restrictions (toujours, même en cas d'échec)
#
# Usage :
#   .\install_xapk.ps1 -XapkPath "C:\Downloads\app.xapk"
#   .\install_xapk.ps1 -XapkPath "C:\Downloads\app.xapk" -Serial RZCT30L9EGJ

param(
    [Parameter(Mandatory = $true)][string]$XapkPath,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$ADB = Join-Path $PSScriptRoot 'adb.exe'
$DPC_PKG = 'com.jo.selfcontrol.ultimate'

function Write-Step($msg)  { Write-Host "[*] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "[+] $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Write-Err2($msg)  { Write-Host "[-] $msg" -ForegroundColor Red }

Write-Host "=== install_xapk.ps1 ===" -ForegroundColor Cyan

# --- 1. Validations ---------------------------------------------------------

if (-not (Test-Path $ADB)) {
    Write-Err2 "adb.exe introuvable a cote du script ($ADB)"
    exit 1
}
if (-not (Test-Path $XapkPath)) {
    Write-Err2 "Fichier XAPK introuvable : $XapkPath"
    exit 1
}
$XapkPath = (Resolve-Path $XapkPath).Path
$xapkSize = [math]::Round((Get-Item $XapkPath).Length / 1MB, 1)
Write-Ok "XAPK : $XapkPath ($xapkSize MB)"

# --- 2. Resolution du device cible -----------------------------------------

$devicesRaw = & $ADB devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" }
$serials = $devicesRaw | ForEach-Object { ($_ -split "`t")[0] }

if ($serials.Count -eq 0) {
    Write-Err2 "Aucun appareil ADB connecte. Branche le tablette/telephone et autorise USB debugging."
    exit 1
}

if ($Serial) {
    if ($serials -notcontains $Serial) {
        Write-Err2 "Serial $Serial absent. Connectes : $($serials -join ', ')"
        exit 1
    }
} else {
    if ($serials.Count -gt 1) {
        Write-Err2 "Plusieurs appareils detectes ($($serials -join ', ')). Precise avec -Serial <id>."
        exit 1
    }
    $Serial = $serials[0]
}
$adbArgs = @('-s', $Serial)
Write-Ok "Cible : $Serial"

# --- 3. ALLOW_INSTALL (lever restrictions DO) -------------------------------

Write-Step "Levee des restrictions install (broadcast ALLOW_INSTALL)..."
# -p <pkg> est obligatoire (Android 8+ : broadcasts implicites custom non delivres sans cible)
& $ADB @adbArgs shell am broadcast -a "$DPC_PKG.ALLOW_INSTALL" -p $DPC_PKG 2>&1 | Out-Null
# Latence pour que le DPC traite le broadcast et clearUserRestriction avant le install
Start-Sleep -Milliseconds 1500

# --- 4. Extraction du XAPK --------------------------------------------------

$tmp = Join-Path $env:TEMP ("xapk_" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
Write-Step "Extraction dans $tmp"

$installSucceeded = $false
try {
    # .xapk est un ZIP. Expand-Archive exige .zip, on cree un alias pour eviter de copier
    $zipAlias = Join-Path $tmp 'package.zip'
    Copy-Item $XapkPath $zipAlias
    Expand-Archive -Path $zipAlias -DestinationPath $tmp -Force
    Remove-Item $zipAlias

    # --- 5. Inspection du contenu -----------------------------------------
    $apks = Get-ChildItem $tmp -Filter '*.apk' -File
    if ($apks.Count -eq 0) {
        # Certains XAPK rangent les apks dans un sous-dossier
        $apks = Get-ChildItem $tmp -Filter '*.apk' -File -Recurse
    }
    if ($apks.Count -eq 0) {
        throw "Aucun fichier .apk trouve dans le XAPK"
    }
    Write-Ok "Trouve $($apks.Count) APK : $((($apks | ForEach-Object Name) -join ', '))"

    # Lecture du manifest.json pour le package_name (utile pour OBB)
    $pkgName = $null
    $manifest = Join-Path $tmp 'manifest.json'
    if (Test-Path $manifest) {
        try {
            $mf = Get-Content $manifest -Raw | ConvertFrom-Json
            if ($mf.package_name) { $pkgName = $mf.package_name }
        } catch {
            Write-Warn2 "manifest.json present mais illisible (continue sans)"
        }
    }
    if ($pkgName) { Write-Ok "Package : $pkgName" }

    # --- 6. Install -------------------------------------------------------
    Write-Step "Installation via adb install-multiple..."
    $apkPaths = $apks | ForEach-Object { $_.FullName }
    $installArgs = $adbArgs + @('install-multiple', '-r', '-t') + $apkPaths
    $installOutput = & $ADB @installArgs 2>&1
    $installExit = $LASTEXITCODE
    Write-Host $installOutput

    if ($installExit -ne 0 -or $installOutput -match 'Failure|Error|INSTALL_FAILED') {
        throw "Echec install (exit=$installExit)"
    }
    Write-Ok "APK(s) installes"
    $installSucceeded = $true

    # --- 7. Push des OBB si presents --------------------------------------
    $obbRoot = Join-Path $tmp 'Android\obb'
    if ((Test-Path $obbRoot) -and $pkgName) {
        $obbPkgDir = Join-Path $obbRoot $pkgName
        if (Test-Path $obbPkgDir) {
            $obbFiles = Get-ChildItem $obbPkgDir -Filter '*.obb' -File
            if ($obbFiles.Count -gt 0) {
                Write-Step "Push de $($obbFiles.Count) fichier(s) OBB..."
                & $ADB @adbArgs shell mkdir -p "/sdcard/Android/obb/$pkgName" 2>&1 | Out-Null
                foreach ($obb in $obbFiles) {
                    & $ADB @adbArgs push $obb.FullName "/sdcard/Android/obb/$pkgName/$($obb.Name)" | Out-Null
                    Write-Ok "  $($obb.Name)"
                }
            }
        }
    }
}
catch {
    Write-Err2 "Echec : $_"
}
finally {
    # --- 8. Re-blocage (TOUJOURS) ----------------------------------------
    Write-Step "Re-blocage (broadcast BLOCK_INSTALL)..."
    & $ADB @adbArgs shell am broadcast -a "$DPC_PKG.BLOCK_INSTALL" -p $DPC_PKG 2>&1 | Out-Null

    # --- 9. Cleanup ------------------------------------------------------
    if (Test-Path $tmp) {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($installSucceeded) {
    Write-Ok "Termine."
    exit 0
} else {
    Write-Err2 "Installation interrompue."
    exit 2
}
