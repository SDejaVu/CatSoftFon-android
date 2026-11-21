# Builds a signed release APK of CatSoftFon - the Android equivalent of
# installer/build-installer.ps1 in the Windows client's repo. Run from anywhere; it figures
# out the project root from its own location.
#
# What it does:
#   1. If keystore.properties doesn't exist yet at the project root, creates a release
#      keystore with keytool (asking you for passwords/alias interactively) and writes
#      keystore.properties pointing at it. Keep that keystore file and its passwords safe -
#      losing them means future updates can never be signed to match an already-installed
#      copy of the app, so Android will refuse to install the update over it.
#   2. Runs ".\gradlew.bat assembleRelease" to produce a signed release APK.
#
# Requirements on this machine:
#   - Android Studio (for the Android SDK / to resolve Gradle dependencies). gradlew.bat
#     itself is already checked into this repo, so you don't need Android Studio to have
#     opened the project first just to get it - but the build still needs the Android SDK,
#     which only Android Studio's own setup wizard installs.
#   - A JDK no newer than 21 to actually RUN Gradle itself (separate from whatever JDK
#     compiles the app's own code). Gradle's own daemon currently tops out around Java 24, so
#     a bundled Android Studio JBR on Java 25+ can't run Gradle at all - it fails with a
#     cryptic error that's just the Java version number ("What went wrong: 25.0.3") instead of
#     a readable message. If you hit that, install a JDK 21 (LTS) separately, e.g. Eclipse
#     Temurin: https://adoptium.net/temurin/releases/?version=21 - this script looks for one
#     automatically (see Find-JdkHome below) and prefers it over Android Studio's own jbr.
#
# Usage:  powershell -ExecutionPolicy Bypass -File installer\build-release-apk.ps1
#         powershell -ExecutionPolicy Bypass -File installer\build-release-apk.ps1 -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot"

param(
    [string]$JdkHome # Optional explicit override if auto-detection picks the wrong JDK.
)

$ErrorActionPreference = "Stop"

$installerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $installerDir
$keystorePropsPath = Join-Path $repoRoot "keystore.properties"

# Gradle's own compatibility ceiling moves over time but lags behind the newest JDKs by
# months; anything above this is assumed too new to run Gradle's daemon reliably, even though
# it's perfectly fine for Android Studio itself to run on.
$MaxSafeJavaMajorVersion = 23

function Get-JavaMajorVersion([string]$javaHome) {
    $javaExe = Join-Path $javaHome "bin\java.exe"
    if (-not (Test-Path $javaExe)) { return $null }
    # "java -version" always prints to stderr - that's normal for java itself, not a real
    # error. But under $ErrorActionPreference = "Stop" (set globally below), PowerShell 7+
    # turns captured stderr lines from a native command into a terminating NativeCommandError.
    # Relax that locally for just this call so version detection doesn't abort the script.
    $oldPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $versionOutput = & $javaExe -version 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $oldPref
    }
    if ($versionOutput -match '"(\d+)(\.\d+)*"') {
        return [int]$Matches[1]
    }
    return $null
}

# Prefers, in order: an explicit -JdkHome param, a known standalone-JDK install location
# (what you get from installing Temurin/Microsoft Build of OpenJDK/Zulu), JAVA_HOME if it's
# already set to something usable, java.exe on PATH, and only then falls back to Android
# Studio's bundled JBR - which is the likeliest one to be "too new" for Gradle, per the note
# above, so it's deliberately the last resort rather than the first guess.
function Find-JdkHome {
    param([string]$explicitOverride)

    if ($explicitOverride) {
        if (Test-Path (Join-Path $explicitOverride "bin\java.exe")) { return $explicitOverride }
        throw "The -JdkHome path you gave ($explicitOverride) doesn't contain bin\java.exe."
    }

    $standaloneCandidates = @()
    foreach ($root in @("${env:ProgramFiles}\Eclipse Adoptium", "${env:ProgramFiles}\Microsoft\jdk-21*", "${env:ProgramFiles}\Zulu", "${env:ProgramFiles}\Java")) {
        if (Test-Path $root) {
            $standaloneCandidates += Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "21" } |
                ForEach-Object { $_.FullName }
        }
    }
    $workingStandalone = $standaloneCandidates |
        Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } |
        Where-Object { (Get-JavaMajorVersion $_) -le $MaxSafeJavaMajorVersion } |
        Select-Object -First 1
    if ($workingStandalone) { return $workingStandalone }

    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        if ((Get-JavaMajorVersion $env:JAVA_HOME) -le $MaxSafeJavaMajorVersion) { return $env:JAVA_HOME }
    }

    $javaOnPath = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($javaOnPath) {
        $fromPath = Split-Path -Parent (Split-Path -Parent $javaOnPath.Source)
        if ((Get-JavaMajorVersion $fromPath) -le $MaxSafeJavaMajorVersion) { return $fromPath }
    }

    $jbrCandidates = @(
        "${env:ProgramFiles}\Android\Android Studio\jbr",
        "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
        "${env:LOCALAPPDATA}\Programs\Android Studio\jbr",
        "${env:ProgramFiles}\Android\Android Studio\jre",
        "${env:LOCALAPPDATA}\Programs\Android Studio\jre"
    )
    $found = $jbrCandidates | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
    if ($found) { return $found } # Last resort even if "too new" - better than nothing, and Find-JdkHome's caller checks the version again and warns.

    $toolboxRoot = "${env:LOCALAPPDATA}\JetBrains\Toolbox\apps"
    if (Test-Path $toolboxRoot) {
        $viaToolbox = Get-ChildItem -Path $toolboxRoot -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue -File |
            Where-Object { $_.FullName -match "[\\/]jbr[\\/]bin[\\/]java\.exe$" } |
            Select-Object -First 1
        if ($viaToolbox) { return Split-Path -Parent (Split-Path -Parent $viaToolbox.FullName) }
    }

    return $null
}

$resolvedJdkHome = Find-JdkHome -explicitOverride $JdkHome
if (-not $resolvedJdkHome) {
    throw "Could not find any JDK (checked -JdkHome, standalone JDK installs, JAVA_HOME, PATH, and Android " + `
          "Studio's bundled jbr/jre). Install a JDK 21, e.g. Eclipse Temurin: " + `
          "https://adoptium.net/temurin/releases/?version=21, then re-run this script."
}
$resolvedVersion = Get-JavaMajorVersion $resolvedJdkHome
if ($resolvedVersion -and $resolvedVersion -gt $MaxSafeJavaMajorVersion) {
    Write-Host "==> Only found Java $resolvedVersion ($resolvedJdkHome), which is likely too new for Gradle to run on." -ForegroundColor Yellow
    Write-Host "    (That's probably Android Studio's own bundled JDK - fine for the IDE, not yet for Gradle's own daemon.)" -ForegroundColor Yellow
    Write-Host "    Install a JDK 21 separately - Eclipse Temurin is a good, free option:" -ForegroundColor Yellow
    Write-Host "    https://adoptium.net/temurin/releases/?version=21" -ForegroundColor Yellow
    Write-Host "    Then re-run this script (it will prefer the JDK 21 automatically), or pass it explicitly:" -ForegroundColor Yellow
    Write-Host "    powershell -ExecutionPolicy Bypass -File installer\build-release-apk.ps1 -JdkHome `"C:\Path\To\jdk-21`"" -ForegroundColor Yellow
    throw "No JDK <= $MaxSafeJavaMajorVersion found."
}
$jdkHome = $resolvedJdkHome
$env:JAVA_HOME = $jdkHome
Write-Host "Using JDK: $jdkHome (Java $resolvedVersion)"
$keyTool = Join-Path $jdkHome "bin\keytool.exe"

# One-time repair for a keystore.properties written by an earlier version of this script: if
# Windows PowerShell (5.1) wrote it with Set-Content -Encoding UTF8, it added a UTF-8
# byte-order-mark Java's Properties.load() doesn't understand, silently breaking the first
# property (storeFile) and causing Gradle to fail with "SigningConfig release is missing
# required property storeFile" even though the file looks fine. Strip the BOM in place if
# present; harmless no-op if the file is already clean.
if (Test-Path $keystorePropsPath) {
    $existingBytes = [System.IO.File]::ReadAllBytes($keystorePropsPath)
    if ($existingBytes.Length -ge 3 -and $existingBytes[0] -eq 0xEF -and $existingBytes[1] -eq 0xBB -and $existingBytes[2] -eq 0xBF) {
        Write-Host "==> Found a UTF-8 BOM at the start of keystore.properties (from an earlier run) - stripping it, this was breaking signing." -ForegroundColor Yellow
        $existingText = [System.Text.Encoding]::UTF8.GetString($existingBytes, 3, $existingBytes.Length - 3)
        [System.IO.File]::WriteAllText($keystorePropsPath, $existingText, (New-Object System.Text.UTF8Encoding($false)))
    }
}

if (-not (Test-Path $keystorePropsPath)) {
    Write-Host "==> No keystore.properties yet - let's create your release keystore." -ForegroundColor Cyan
    Write-Host "    (Only needs doing once. Keep the resulting keystore file and passwords safe -"
    Write-Host "     every future update has to be signed with the SAME keystore.)"
    Write-Host ""

    if (-not (Test-Path $keyTool)) {
        throw "Found a JDK at $jdkHome but no keytool.exe inside it. Install a full JDK and re-run this script."
    }

    $keystoreFileName = Read-Host "Keystore file name [catsoftfon-release.keystore]"
    if ([string]::IsNullOrWhiteSpace($keystoreFileName)) { $keystoreFileName = "catsoftfon-release.keystore" }
    $keystorePath = Join-Path $repoRoot $keystoreFileName

    $keyAlias = Read-Host "Key alias [catsoftfon]"
    if ([string]::IsNullOrWhiteSpace($keyAlias)) { $keyAlias = "catsoftfon" }

    $storePasswordSecure = Read-Host "Keystore password (min. 6 characters)" -AsSecureString
    $storePassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePasswordSecure)
    )
    $keyPassword = $storePassword # Same password for the key itself - simplest, still secure.

    $validityDays = 365 * 25 # 25 years - matches how long-lived the Windows installer's own AppId GUID is meant to be.

    Write-Host ""
    Write-Host "==> keytool will now ask for your name/organization - this becomes part of the" -ForegroundColor Cyan
    Write-Host "    certificate but isn't shown to users installing the app; any answer is fine." -ForegroundColor Cyan
    Write-Host "    At the final 'Is CN=... correct?' prompt, type yes (pressing Enter alone keeps" -ForegroundColor Cyan
    Write-Host "    re-asking, since the default there is 'no')." -ForegroundColor Cyan
    Write-Host ""

    & $keyTool -genkeypair -v `
        -keystore $keystorePath `
        -alias $keyAlias `
        -keyalg RSA -keysize 2048 `
        -validity $validityDays `
        -storepass $storePassword `
        -keypass $keyPassword
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }

    $keystorePropsContent = @"
storeFile=$keystoreFileName
storePassword=$storePassword
keyAlias=$keyAlias
keyPassword=$keyPassword
"@
    # Not Set-Content -Encoding UTF8: on Windows PowerShell 5.1 (as opposed to PowerShell 7+),
    # that writes a UTF-8 byte-order-mark at the start of the file. Java's Properties.load()
    # doesn't know to skip that BOM, so it ends up glued onto the first key ("storeFile"),
    # which then silently fails to match when Gradle looks it up - producing exactly the
    # "SigningConfig release is missing required property storeFile" error from Gradle, even
    # though the file visually looks correct. Writing via .NET with UTF8Encoding($false)
    # guarantees no BOM, on any PowerShell version.
    [System.IO.File]::WriteAllText($keystorePropsPath, $keystorePropsContent + "`n", (New-Object System.Text.UTF8Encoding($false)))

    Write-Host "==> Created $keystoreFileName and keystore.properties." -ForegroundColor Green
    Write-Host ""
}

# Sanity-check keystore.properties ourselves before handing off to Gradle: Gradle's own error
# for anything wrong here is just "SigningConfig release is missing required property
# storeFile", which doesn't say WHY (missing key? wrong encoding? file moved?). Parse it the
# same simple way Java's Properties.load() does (key=value lines) and check what it resolves
# to, so a problem shows up here with a specific reason instead of that generic message.
Write-Host "==> Checking keystore.properties..." -ForegroundColor Cyan
$propsRaw = [System.IO.File]::ReadAllText($keystorePropsPath)
$propsMap = @{}
foreach ($line in ($propsRaw -split "`r?`n")) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
    $idx = $trimmed.IndexOf("=")
    if ($idx -lt 0) { continue }
    $key = $trimmed.Substring(0, $idx).Trim()
    $value = $trimmed.Substring($idx + 1).Trim()
    $propsMap[$key] = $value
}
foreach ($requiredKey in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
    if (-not $propsMap.ContainsKey($requiredKey) -or [string]::IsNullOrWhiteSpace($propsMap[$requiredKey])) {
        throw "keystore.properties is missing or has an empty '$requiredKey' value. Raw file content was:`n$propsRaw`nDelete keystore.properties and re-run this script to recreate it."
    }
}
$resolvedKeystoreFile = Join-Path $repoRoot $propsMap["storeFile"]
if (-not (Test-Path $resolvedKeystoreFile)) {
    throw "keystore.properties points at storeFile=$($propsMap["storeFile"]), which resolves to " + `
          "$resolvedKeystoreFile - but no file exists there. Either restore your original " + `
          ".keystore file at that path, or delete keystore.properties and re-run this script " + `
          "to create a new one (note: a new keystore can't sign updates for an app already " + `
          "installed from the old one)."
}
Write-Host "==> keystore.properties OK - storeFile=$($propsMap["storeFile"]) (found), keyAlias=$($propsMap["keyAlias"])" -ForegroundColor Green

Write-Host "==> Building signed release APK (gradlew assembleRelease)..." -ForegroundColor Cyan

Push-Location $repoRoot
try {
    $gradlew = Join-Path $repoRoot "gradlew.bat"
    if (Test-Path $gradlew) {
        & $gradlew assembleRelease
    } else {
        $gradleOnPath = Get-Command "gradle.bat" -ErrorAction SilentlyContinue
        if (-not $gradleOnPath) { $gradleOnPath = Get-Command "gradle" -ErrorAction SilentlyContinue }
        if (-not $gradleOnPath) {
            throw "Neither gradlew.bat nor a system-wide gradle was found in $repoRoot."
        }
        Write-Host "    gradlew.bat not found, falling back to system Gradle: $($gradleOnPath.Source)"
        & $gradleOnPath.Source assembleRelease
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$outputApk = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
Write-Host ""
if (Test-Path $outputApk) {
    Write-Host "==> Done: $outputApk" -ForegroundColor Green
} else {
    Write-Host "==> Gradle finished, but the expected APK wasn't found at $outputApk - check the" -ForegroundColor Yellow
    Write-Host "    build output above for the actual path (it can shift if you change applicationId/flavors)."
}
