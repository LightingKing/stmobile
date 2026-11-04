param([switch]$Lan, [switch]$AllowPublicNetwork)
# Registers a task that starts the writing server when this Windows user signs in and keeps it
# running (run.ps1 restarts it after a crash). Run elevated once so the LAN firewall rule can be set.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\windows-common.ps1')
$dir = Initialize-PocketDataDir
if ($Lan) { Set-PocketLanHost $dir | Out-Null }
$config = Read-PocketConfig $dir
$lanMode = $config.lanMode -eq $true
if ($lanMode) { Set-PocketFirewallRule -Port $config.port -AllowPublicNetwork:$AllowPublicNetwork }

$argument = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PocketRunScript`""
if ($lanMode) { $argument += ' -Lan' }
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $PocketWindowsPowerShell -Argument $argument -WorkingDirectory $PocketRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
  -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $PocketTaskName -Description 'Pocket Tavern writing server for the phone app (restarts after crashes).' `
  -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null

# A copy started by hand would hold the port; hand the server over to the task.
& (Join-Path $PocketRoot 'stop.ps1') | Out-Null
Start-ScheduledTask -TaskName $PocketTaskName
if (!(Wait-PocketHealth $config.port)) { throw 'The task started but the server did not become healthy. See server.log and supervisor.log in the private directory.' }
$config = Read-PocketConfig $dir
$urls = @()
if ($lanMode) { $urls += "http://$($config.host):$($config.port)/v1" }
Write-PocketConnectionFile $dir $urls
Write-Output "Autostart installed as scheduled task '$PocketTaskName' for $user."
Write-Output "Local health: http://127.0.0.1:$($config.port)/healthz"
foreach ($url in $urls) { Write-Output "Phone Base URL: $url" }
Write-Output "Private connection instructions: $(Join-Path $dir '手机连接.txt')"
