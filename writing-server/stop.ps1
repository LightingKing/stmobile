$ErrorActionPreference='Stop'
$taskData=Join-Path $env:LOCALAPPDATA 'PocketTavernServer'
$taskPidFile=Join-Path $taskData 'server.pid'
if(!(Test-Path -LiteralPath $taskPidFile)){Write-Output 'No writing service PID file.'; exit 0}
$taskId=0
if(![int]::TryParse([IO.File]::ReadAllText($taskPidFile).Trim(),[ref]$taskId)){throw 'Invalid PID file; no process stopped.'}
$taskMain=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'src\main.mjs'))
$taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskId" -ErrorAction SilentlyContinue
if(!$taskProcess){Write-Output 'Writing service is already stopped.'; exit 0}
if(!$taskProcess.CommandLine.Contains($taskMain)){throw 'PID belongs to another program; no process stopped.'}
Stop-Process -Id $taskId
Write-Output 'Writing service stopped. Existing run files remain in the private directory.'
