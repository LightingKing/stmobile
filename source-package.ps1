param([string]$Destination='')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot=[IO.Path]::GetFullPath($PSScriptRoot)
if (!$Destination) { $Destination=Join-Path $taskRoot 'dist\PocketTavern-0.1.52-source.zip' }
$taskDestination=[IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskDestination)) -Force | Out-Null
$taskFiles=@()
$taskTop=@('.gitignore','.gitattributes','README.md','build.gradle.kts','settings.gradle.kts','gradle.properties','build.ps1','package.ps1','source-package.ps1','gradlew','gradlew.bat')
foreach($taskName in $taskTop) { $taskPath=Join-Path $taskRoot $taskName; if(Test-Path -LiteralPath $taskPath -PathType Leaf) { $taskFiles+=Get-Item -LiteralPath $taskPath } }
foreach($taskDir in @('app\src','docs','examples','gradle\wrapper')) {
    $taskPath=Join-Path $taskRoot $taskDir
    if(Test-Path -LiteralPath $taskPath) { $taskFiles+=Get-ChildItem -LiteralPath $taskPath -File -Recurse }
}
$taskFiles+=Get-Item -LiteralPath (Join-Path $taskRoot 'app\build.gradle.kts')
$taskServer=Join-Path $taskRoot 'writing-server'
if(Test-Path -LiteralPath $taskServer) {
    $taskFiles+=Get-ChildItem -LiteralPath $taskServer -File -Recurse | Where-Object {
        $_.FullName -notmatch '[\\/](node_modules|runtime|writing-server-data)[\\/]' -and
        $_.Extension -notin @('.log','.jks','.keystore') -and $_.Name -notlike '.env*'
    }
}
$taskStream=[IO.File]::Open($taskDestination,[IO.FileMode]::Create)
$taskArchive=[IO.Compression.ZipArchive]::new($taskStream,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach($taskFile in $taskFiles) {
        $taskFull=[IO.Path]::GetFullPath($taskFile.FullName)
        if(!$taskFull.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Source path escaped project.' }
        if($taskFile.Extension -in @('.jks','.keystore','.log')) { throw 'Unexpected sensitive or generated file in source list.' }
        $taskRelative=[IO.Path]::GetRelativePath($taskRoot,$taskFull).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive,$taskFull,"pocket-tavern/$taskRelative",[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $taskArchive.Dispose(); $taskStream.Dispose() }
Write-Output $taskDestination
Write-Output ('SHA256 '+(Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash)
