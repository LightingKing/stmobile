param([switch]$Tailnet, [switch]$Lan, [switch]$AllowPublicNetwork)
# Starts the writing server in the background (through the logon task when it is installed) and
# writes the private phone connection note. -Lan also serves this PC's LAN address.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\windows-common.ps1')
$dir = Initialize-PocketDataDir
if ($Lan) { Set-PocketLanHost $dir | Out-Null }
$config = Read-PocketConfig $dir
$lanMode = $config.lanMode -eq $true
if ($Lan) { Set-PocketFirewallRule -Port $config.port -AllowPublicNetwork:$AllowPublicNetwork }
if (!(Test-PocketHealth $config.port)) {
  if (Get-ScheduledTask -TaskName $PocketTaskName -ErrorAction SilentlyContinue) {
    Start-ScheduledTask -TaskName $PocketTaskName
  } else {
    $arguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$PocketRunScript`"")
    if ($lanMode) { $arguments += '-Lan' }
    Start-Process -FilePath $PocketWindowsPowerShell -ArgumentList $arguments -WindowStyle Hidden | Out-Null
  }
  if (!(Wait-PocketHealth $config.port)) { throw 'Writing service did not become healthy. See server.log and supervisor.log in the private directory.' }
  $config = Read-PocketConfig $dir
}
$urls = @()
if ($lanMode) { $urls += "http://$($config.host):$($config.port)/v1" }
if ($Tailnet) {
  $tailscale = (& tailscale status --json | ConvertFrom-Json)
  if ($LASTEXITCODE -ne 0 -or $tailscale.BackendState -ne 'Running') { throw 'Tailscale must be signed in on this PC.' }
  $existing = (& tailscale serve status --json | ConvertFrom-Json)
  if ($existing.TCP.'8443' -or $existing.Web.PSObject.Properties.Name -match ':8443$') {
    if (!($existing | ConvertTo-Json -Depth 20).Contains("http://127.0.0.1:$($config.port)")) { throw 'Tailscale port 8443 already has another service; no configuration changed.' }
  }
  & tailscale serve --bg --https=8443 "http://127.0.0.1:$($config.port)"
  if ($LASTEXITCODE -ne 0) { throw 'Tailscale HTTPS setup needs attention. Local server remains available.' }
  $urls += "https://$($tailscale.Self.DNSName.TrimEnd('.')):8443/v1"
}
Write-PocketConnectionFile $dir $urls
Write-Output "Local health: http://127.0.0.1:$($config.port)/healthz"
foreach ($url in $urls) { Write-Output "Phone Base URL: $url" }
Write-Output "Private connection instructions: $(Join-Path $dir '手机连接.txt')"
