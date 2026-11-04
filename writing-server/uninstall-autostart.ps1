param([switch]$RemoveFirewallRule)
# Stops the writing server and removes its logon task. Private data, run records and backups stay.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\windows-common.ps1')
& (Join-Path $PocketRoot 'stop.ps1')
if (Get-ScheduledTask -TaskName $PocketTaskName -ErrorAction SilentlyContinue) {
  Unregister-ScheduledTask -TaskName $PocketTaskName -Confirm:$false
  Write-Output "Removed scheduled task '$PocketTaskName'."
} else { Write-Output 'No autostart task was installed.' }
if ($RemoveFirewallRule) {
  if (!(Test-PocketAdmin)) { throw 'Run as administrator to remove the firewall rule.' }
  Get-NetFirewallRule -Name $PocketFirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  Write-Output "Removed firewall rule '$PocketFirewallRule'."
}
