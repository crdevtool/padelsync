<#
.SYNOPSIS
    Builds the Android phone app and the Wear OS app on this computer.

.DESCRIPTION
    Without options it makes the two test builds (debug APKs), the ones that
    are installed with adb.

    With -Release it makes the two signed app bundles (.aab) that are
    uploaded to Google Play. That needs the upload keystore and its password,
    which is asked for and never stored.

    Either way the results are copied into the "builds" folder.

    If Windows refuses to run the script, start it like this:
        powershell -ExecutionPolicy Bypass -File .\build-android.ps1

.PARAMETER Release
    Build the signed bundles for Google Play in place of the test builds.

.PARAMETER ReleaseNumber
    The release number the version codes are made from:
    phone = number x 10 + 1, watch = number x 10 + 2.
    Google Play refuses a version code it has already been given, so use a
    number higher than every release uploaded so far. Play Console shows the
    last version code; drop its last digit to get that release's number.

.PARAMETER Keystore
    The upload keystore file. By default padelsync-upload.jks in your user
    folder.

.PARAMETER KeyAlias
    The name of the key inside the keystore.

.EXAMPLE
    .\build-android.ps1
    Test builds: builds\PadelSync-phone.apk and builds\PadelSync-watch.apk.

.EXAMPLE
    .\build-android.ps1 -Release -ReleaseNumber 4
    Signed bundles with version codes 41 (phone) and 42 (watch).
#>
[CmdletBinding()]
param(
    [switch]$Release,
    [int]$ReleaseNumber = 0,
    [string]$Keystore = (Join-Path $HOME "padelsync-upload.jks"),
    [string]$KeyAlias = "padelsync-upload"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$onWindows = ($env:OS -eq "Windows_NT")

function Stop-WithMessage([string]$message) {
    Write-Host ""
    Write-Host "STOPPED: $message" -ForegroundColor Red
    exit 1
}

# --- Java and the Android SDK ------------------------------------------------
# Android Studio brings its own Java; use it unless another one is set up.
if (-not $env:JAVA_HOME) {
    $studioJava = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $studioJava) { $env:JAVA_HOME = $studioJava }
}
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Stop-WithMessage "Java was not found. Install Android Studio, or set JAVA_HOME to a JDK 17 or newer."
}
# The build finds the Android SDK through local.properties, which Android
# Studio writes, or through ANDROID_HOME.
if (-not (Test-Path (Join-Path $root "local.properties")) -and -not $env:ANDROID_HOME) {
    $studioSdk = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" } else { "" }
    if ($studioSdk -and (Test-Path $studioSdk)) {
        $env:ANDROID_HOME = $studioSdk
    } else {
        Stop-WithMessage "The Android SDK was not found. Open this folder once in Android Studio, or set ANDROID_HOME."
    }
}

$gradle = if ($onWindows) { Join-Path $root "gradlew.bat" } else { Join-Path $root "gradlew" }
$builds = Join-Path $root "builds"
New-Item -ItemType Directory -Force -Path $builds | Out-Null

# Runs the build and says whether it succeeded. Its output goes straight to
# the screen.
function Invoke-Gradle([string[]]$tasks) {
    Push-Location $root
    try {
        & $gradle @tasks | Out-Host
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
}
$buildFailedMessage = "The build failed; the reason is in the lines above."

# Copies one build result into the builds folder under its delivery name.
function Copy-Result([string]$from, [string]$name) {
    $source = Join-Path $root $from
    if (-not (Test-Path $source)) { Stop-WithMessage "The build did not produce $from." }
    $target = Join-Path $builds $name
    Copy-Item $source $target -Force
    $size = [math]::Round((Get-Item $target).Length / 1MB, 1)
    Write-Host ("  builds\{0}  ({1} MB)" -f $name, $size)
}

# --- Test builds --------------------------------------------------------------
if (-not $Release) {
    Write-Host "Building the test builds (debug APKs)..."
    if (-not (Invoke-Gradle @(":mobile:assembleDebug", ":wear:assembleDebug"))) {
        Stop-WithMessage $buildFailedMessage
    }
    Write-Host ""
    Write-Host "Done:"
    Copy-Result "mobile/build/outputs/apk/debug/mobile-debug.apk" "PadelSync-phone.apk"
    Copy-Result "wear/build/outputs/apk/debug/wear-debug.apk" "PadelSync-watch.apk"
    Write-Host "Install them with adb; see docs\install.md."
    exit 0
}

# --- Signed bundles for Google Play -------------------------------------------
if ($ReleaseNumber -lt 1) {
    Stop-WithMessage "Give the release number, for example: .\build-android.ps1 -Release -ReleaseNumber 4. It must be higher than every release already uploaded to Google Play."
}
if (-not (Test-Path $Keystore)) {
    Stop-WithMessage "The upload keystore was not found at $Keystore. Pass its location with -Keystore."
}

$version = ""
foreach ($line in Get-Content (Join-Path $root "gradle.properties")) {
    if ($line -match "^\s*padelsync\.versionName\s*=\s*(.+?)\s*$") { $version = $Matches[1] }
}
if (-not $version) { Stop-WithMessage "padelsync.versionName is missing from gradle.properties." }
$phoneCode = $ReleaseNumber * 10 + 1
$watchCode = $ReleaseNumber * 10 + 2

# The password is typed here, handed to the build through its environment,
# and removed again when the build ends. It is never written to a file.
$askedForPassword = $false
if (-not $env:PLAY_KEYSTORE_PASSWORD) {
    $secure = Read-Host "Keystore password" -AsSecureString
    $env:PLAY_KEYSTORE_PASSWORD = (New-Object System.Net.NetworkCredential("", $secure)).Password
    $askedForPassword = $true
    if (-not $env:PLAY_KEYSTORE_PASSWORD) { Stop-WithMessage "No password was entered." }
}
$keyPasswordWasSet = [bool]$env:PLAY_KEY_PASSWORD
# A keystore made as the docs describe has one password for the file and the key.
if (-not $keyPasswordWasSet) { $env:PLAY_KEY_PASSWORD = $env:PLAY_KEYSTORE_PASSWORD }
$env:PLAY_KEYSTORE_FILE = (Resolve-Path $Keystore).Path
$env:PLAY_KEY_ALIAS = $KeyAlias
$env:PADELSYNC_RELEASE_NUMBER = "$ReleaseNumber"

$built = $false
try {
    Write-Host "Building version $version, release $ReleaseNumber (version codes $phoneCode and $watchCode)..."
    $built = Invoke-Gradle @(":mobile:bundleRelease", ":wear:bundleRelease")
} finally {
    # Whatever happened, the password does not stay behind in this window.
    if ($askedForPassword) { Remove-Item Env:PLAY_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue }
    if (-not $keyPasswordWasSet) { Remove-Item Env:PLAY_KEY_PASSWORD -ErrorAction SilentlyContinue }
    Remove-Item Env:PLAY_KEYSTORE_FILE, Env:PLAY_KEY_ALIAS, Env:PADELSYNC_RELEASE_NUMBER -ErrorAction SilentlyContinue
}
if (-not $built) { Stop-WithMessage $buildFailedMessage }

$phoneBundle = "mobile/build/outputs/bundle/release/mobile-release.aab"
$watchBundle = "wear/build/outputs/bundle/release/wear-release.aab"

# A bundle that is not signed would be refused by Google Play; say so here.
$jarsigner = ""
if ($env:JAVA_HOME) {
    $name = if ($onWindows) { "jarsigner.exe" } else { "jarsigner" }
    $candidate = Join-Path (Join-Path $env:JAVA_HOME "bin") $name
    if (Test-Path $candidate) { $jarsigner = $candidate }
}
if (-not $jarsigner) {
    $found = Get-Command jarsigner -ErrorAction SilentlyContinue
    if ($found) { $jarsigner = $found.Source }
}
if ($jarsigner) {
    foreach ($bundle in @($phoneBundle, $watchBundle)) {
        $path = Join-Path $root $bundle
        if (-not (Test-Path $path)) { Stop-WithMessage "The build did not produce $bundle." }
        # Java's tools write notes to the error stream, which Windows
        # PowerShell would otherwise treat as a failure of this script.
        $ErrorActionPreference = "Continue"
        $report = (& $jarsigner -verify $path 2>&1 | Out-String)
        $ErrorActionPreference = "Stop"
        if ($report -notmatch "jar verified") {
            Stop-WithMessage "$bundle is not signed. Check the keystore, its password and the key alias."
        }
    }
    Write-Host "Both bundles are signed."
} else {
    Write-Host "Note: jarsigner was not found, so the signatures were not checked."
}

Write-Host ""
Write-Host "Done:"
Copy-Result $phoneBundle "PadelSync-phone-$version-$phoneCode.aab"
Copy-Result $watchBundle "PadelSync-watch-$version-$watchCode.aab"
Write-Host "Upload the phone bundle to Internal testing and the watch bundle to the Wear OS track;"
Write-Host "the steps are in docs\architecture.md, section 9."
