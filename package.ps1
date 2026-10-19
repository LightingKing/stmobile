param([switch]$Offline,[string]$AndroidMirror='')
$ErrorActionPreference='Stop'
$taskSigning=Join-Path $env:USERPROFILE '.android\pocket-tavern-signing'
New-Item -ItemType Directory -Path $taskSigning -Force | Out-Null
$taskStore=Join-Path $taskSigning 'release.jks'
$taskPasswordFile=Join-Path $taskSigning 'password.dpapi.txt'
if (!(Test-Path -LiteralPath $taskPasswordFile)) {
    if(Test-Path -LiteralPath $taskStore) { throw 'Signing store exists without its password file. Preserve the store and recover the password.' }
    $taskBytes=New-Object byte[] 36
    [Security.Cryptography.RandomNumberGenerator]::Fill($taskBytes)
    $taskPassword=[Convert]::ToBase64String($taskBytes)
    ConvertTo-SecureString $taskPassword -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $taskPasswordFile -Encoding utf8
} else {
    $taskSecure=(Get-Content -LiteralPath $taskPasswordFile -Raw).Trim() | ConvertTo-SecureString
    $taskPassword=[System.Net.NetworkCredential]::new('', $taskSecure).Password
}
$env:POCKET_SIGNING_FILE=$taskStore
$env:POCKET_SIGNING_PASSWORD=$taskPassword
try {
    $taskJava=$env:JAVA_HOME
    if(!$taskJava -and (Test-Path -LiteralPath 'I:\jdk1.17\bin\keytool.exe')) { $taskJava='I:\jdk1.17' }
    if(!$taskJava -or !(Test-Path -LiteralPath (Join-Path $taskJava 'bin\keytool.exe'))) { throw 'Install JDK 17 and set JAVA_HOME.' }
    if(!(Test-Path -LiteralPath $taskStore)) {
        & (Join-Path $taskJava 'bin\keytool.exe') -genkeypair -keystore $taskStore -storepass:env POCKET_SIGNING_PASSWORD -keypass:env POCKET_SIGNING_PASSWORD -alias pocket-tavern -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Pocket Tavern Personal, OU=Development, O=Personal, C=CN'
        if($LASTEXITCODE -ne 0) { throw 'Unable to create signing key.' }
    }
    & (Join-Path $PSScriptRoot 'build.ps1') -WithTests -Release -Offline:$Offline -AndroidMirror $AndroidMirror
    $taskDist=Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Path $taskDist -Force | Out-Null
    $taskApk=Join-Path $taskDist 'PocketTavern-0.1.4.apk'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk') -Destination $taskApk -Force
    $taskSdk=if($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $taskSigner=Join-Path $taskSdk 'build-tools\35.0.0\apksigner.bat'
    & $taskSigner verify --verbose $taskApk
    if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $taskHash=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash
    "$taskHash  PocketTavern-0.1.4.apk" | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS.txt') -Encoding utf8
    Write-Output $taskApk
    Write-Output "SHA256 $taskHash"
} finally {
    Remove-Item Env:POCKET_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:POCKET_SIGNING_FILE -ErrorAction SilentlyContinue
    $taskPassword=$null
}
