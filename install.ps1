# Cozy Cat Launcher installer for Windows.
#
# Installs the launcher on an Android TV / Google TV connected by USB (or by network adb).
# Run from PowerShell:
#   irm https://raw.githubusercontent.com/AbshkPskr/cozy-cat-launcher/main/install.ps1 | iex
# or, from a clone of the repo:
#   .\install.ps1                  # install and open it
#   .\install.ps1 -SetHome         # also make it the TV's home screen
#   .\install.ps1 -Apk path\to.apk # install a local build instead of the latest release
#   .\install.ps1 -Restore         # give Home back to the TV's own launcher

param(
    [switch]$SetHome,
    [switch]$Restore,
    [string]$Apk = '',
    [string]$Device = ''
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Package = 'com.cozycat.launcher'
$Activity = "$Package/.MainActivity"
$ReleaseUrl = 'https://github.com/AbshkPskr/cozy-cat-launcher/releases/latest/download/CozyCatLauncher.apk'
$Work = Join-Path $env:LOCALAPPDATA 'CozyCatLauncher'
New-Item -ItemType Directory -Force $Work | Out-Null

function Say($msg) { Write-Host "  $msg" }
function Fail($msg) { Write-Host "`n  ERROR: $msg`n" -ForegroundColor Red; exit 1 }

# ---- 1. Find adb (PATH, Android SDK, or download Google's platform-tools) ---------------------
function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
                     "$Work\platform-tools\adb.exe")) {
        if (Test-Path $p) { return $p }
    }
    Say 'adb not found, downloading Google platform-tools...'
    $zip = Join-Path $Work 'platform-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive $zip -DestinationPath $Work -Force
    Remove-Item $zip
    return "$Work\platform-tools\adb.exe"
}
$adb = Find-Adb
Say "Using adb: $adb"
# Run adb without PowerShell turning its normal stderr messages into errors; returns text lines.
function Adb {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $adb @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
}

# ---- 2. Find the TV -------------------------------------------------------------------------
Adb start-server | Out-Null
$lines = (Adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
if ($Device) { $lines = $lines | Where-Object { $_ -like "$Device*" } }
if (-not $lines) { Fail 'No TV found. Turn on USB debugging on the TV, connect the cable, then run this again.' }
$unauth = $lines | Where-Object { $_ -match 'unauthorized' }
if ($unauth) { Fail 'The TV is asking to allow USB debugging. Pick "Always allow from this computer" on the TV and run this again.' }
$ready = @($lines | Where-Object { $_ -match '\sdevice$' })
if ($ready.Count -gt 1 -and -not $Device) { Fail "More than one device is connected. Run again with -Device <serial>: $($ready -join ', ')" }
$serial = ($ready[0] -split '\s+')[0]
$sdk = [int](Adb -s $serial shell getprop ro.build.version.sdk | Select-Object -First 1).Trim()
$model = (Adb -s $serial shell getprop ro.product.model | Select-Object -First 1).Trim()
Say "Found TV: $model (Android API $sdk)"
if ($sdk -lt 28) { Fail 'Cozy Cat Launcher needs Android 9 or newer.' }

if ($Restore) {
    $homes = Adb -s $serial shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
    $stock = $homes | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '/' -and $_ -notmatch $Package -and $_ -notmatch 'FallbackHome' } | Select-Object -First 1
    if (-not $stock) { Fail 'No other home screen found on the TV.' }
    Adb -s $serial shell cmd package set-home-activity $stock | Out-Null
    Adb -s $serial shell input keyevent KEYCODE_HOME
    Say "Home screen set back to $stock. (Cozy Cat stays installed; uninstall with: adb uninstall $Package)"
    exit 0
}

# ---- 3. Get the APK -------------------------------------------------------------------------
if (-not $Apk) {
    # Use a local build when run from a clone of the repo (not when piped through iex)
    $local = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk' } else { '' }
    if ($local -and (Test-Path $local)) { $Apk = $local }
    else {
        $Apk = Join-Path $Work 'CozyCatLauncher.apk'
        Say 'Downloading the latest Cozy Cat Launcher release...'
        Invoke-WebRequest $ReleaseUrl -OutFile $Apk
    }
}
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk" }

# ---- 4. Install -----------------------------------------------------------------------------
Say 'Installing on the TV (this can take a minute)...'
$out = Adb -s $serial install -r $Apk | Out-String
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Say 'An older Cozy Cat with a different signature is installed; replacing it...'
    Adb -s $serial uninstall $Package | Out-Null
    $out = Adb -s $serial install $Apk | Out-String
}
if ($out -notmatch 'Success') { Fail "Install failed:`n$out" }
Say 'Installed.'
# Pre-compile for smooth animation on slow TV chips (harmless if it fails)
Adb -s $serial shell cmd package compile -m speed -f $Package | Out-Null

# ---- 5. Optional: make it the home screen, then open it -------------------------------------
if ($SetHome) {
    $r = Adb -s $serial shell cmd package set-home-activity $Activity | Out-String
    if ($r -match 'Success') { Say 'Cozy Cat is now the home screen. Run with -Restore to switch back.' }
    else { Say 'This TV did not accept the change from the computer. Press Home on the remote and pick Cozy Cat -> Always.' }
}
Adb -s $serial shell am start -n $Activity | Out-Null
Write-Host "`n  Done! Cozy Cat Launcher is open on your TV." -ForegroundColor Green
if (-not $SetHome) { Say 'To use it as your home screen run again with -SetHome, or open it any time from the TV''s Apps list.' }
