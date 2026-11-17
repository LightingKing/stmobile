param()
$ErrorActionPreference = 'Stop'

# Resolve JDK 17
$taskJava = if (Test-Path -LiteralPath 'I:\jdk1.17\bin\java.exe') { 'I:\jdk1.17' } else { throw 'JDK 17 not found at I:\jdk1.17' }
$env:JAVA_HOME = $taskJava

# Read signing store + DPAPI password (same logic as package.ps1)
$taskSigning    = Join-Path $env:USERPROFILE '.android\pocket-tavern-signing'
$taskStore      = Join-Path $taskSigning 'release.jks'
$taskPasswordFile = Join-Path $taskSigning 'password.dpapi.txt'
if (!(Test-Path -LiteralPath $taskPasswordFile)) {
    if (Test-Path -LiteralPath $taskStore) { throw 'Signing store exists without its password file.' }
    $taskBytes = New-Object byte[] 36
    [Security.Cryptography.RandomNumberGenerator]::Fill($taskBytes)
    $taskPassword = [Convert]::ToBase64String($taskBytes)
    New-Item -ItemType Directory -Path $taskSigning -Force | Out-Null
    ConvertTo-SecureString $taskPassword -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $taskPasswordFile -Encoding utf8
    Write-Host "Created new signing key at $taskStore"
} else {
    $taskSecure   = (Get-Content -LiteralPath $taskPasswordFile -Raw).Trim() | ConvertTo-SecureString
    $taskPassword = [System.Net.NetworkCredential]::new('', $taskSecure).Password
}

if (!(Test-Path -LiteralPath $taskStore)) {
    New-Item -ItemType Directory -Path $taskSigning -Force | Out-Null
    & (Join-Path $taskJava 'bin\keytool.exe') -genkeypair -keystore $taskStore -storepass:env POCKET_SIGNING_PASSWORD -keypass:env POCKET_SIGNING_PASSWORD -alias pocket-tavern -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Pocket Tavern Personal, OU=Development, O=Personal, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create signing key.' }
}

$env:POCKET_SIGNING_FILE     = $taskStore
$env:POCKET_SIGNING_PASSWORD = $taskPassword

try {
    $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $env:ANDROID_HOME = $taskSdk

    # Call gradlew.bat directly — same dir, JAVA_HOME already set above
    $taskGradle = Join-Path $PSScriptRoot 'gradlew.bat'
    & $taskGradle -p $PSScriptRoot --no-daemon --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }

    $taskApk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
    $taskBuildGradle = Join-Path $PSScriptRoot 'app\build.gradle.kts'
    $taskVersion = [regex]::Match((Get-Content -LiteralPath $taskBuildGradle -Raw), '(?m)^\s*versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $taskDest = Join-Path $env:USERPROFILE "Desktop\PocketTavern-$taskVersion-release.apk"
    Copy-Item -LiteralPath $taskApk -Destination $taskDest -Force
    Write-Host "Release APK: $taskDest"
    $taskHash = (Get-FileHash -LiteralPath $taskDest -Algorithm SHA256).Hash
    Write-Host "SHA256: $taskHash"
} finally {
    Remove-Item Env:POCKET_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:POCKET_SIGNING_FILE     -ErrorAction SilentlyContinue
    $taskPassword = $null
}
