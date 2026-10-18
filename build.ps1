param([switch]$WithTests,[switch]$Release,[switch]$Offline,[string]$AndroidMirror='')
$ErrorActionPreference = 'Stop'
$taskJava = $env:JAVA_HOME
if (!$taskJava -and (Test-Path -LiteralPath 'I:\jdk1.17\bin\java.exe')) { $taskJava = 'I:\jdk1.17' }
if (!$taskJava -or !(Test-Path -LiteralPath (Join-Path $taskJava 'bin\java.exe'))) { throw 'Install JDK 17 and set JAVA_HOME.' }
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$taskGradle = Join-Path $PSScriptRoot 'gradlew.bat'
if (!(Test-Path -LiteralPath $taskGradle)) {
    $taskGradle = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-bin') -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (!$taskGradle) { throw 'Gradle 8.14.3 is required; run the bundled Gradle wrapper.' }
$env:JAVA_HOME = $taskJava
$env:ANDROID_HOME = $taskSdk
$taskTargets = @('assembleDebug','lintDebug')
if ($WithTests) { $taskTargets += @('testDebugUnitTest','assembleDebugAndroidTest') }
if ($Release) { $taskTargets += 'assembleRelease' }
if ($AndroidMirror) { $taskTargets += "-PandroidMirror=$AndroidMirror" }
if ($Offline) { $taskTargets += '--offline' }
& $taskGradle -p $PSScriptRoot --no-daemon --console=plain @taskTargets
if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
