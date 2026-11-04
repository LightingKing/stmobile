$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\windows-common.ps1')
$dir = Get-PocketDataDir
$server = Get-PocketServerProcess $dir
$supervisor = Get-PocketSupervisorProcess $dir
if (!$server -and !$supervisor) { Write-Output 'Writing service is already stopped.'; exit 0 }
$stopFile = Join-Path $dir 'stop.request'
[IO.File]::WriteAllText($stopFile, 'stop')
# The server lets generations in progress finish for up to 60 s before it exits.
if ($server) {
  Write-Output 'Stopping the writing service (running generations get up to 60 s to finish)...'
  Wait-Process -Id $server.ProcessId -Timeout 75 -ErrorAction SilentlyContinue
  if (Get-Process -Id $server.ProcessId -ErrorAction SilentlyContinue) { Stop-Process -Id $server.ProcessId -Force }
}
if ($supervisor) {
  Wait-Process -Id $supervisor.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
  if (Get-Process -Id $supervisor.ProcessId -ErrorAction SilentlyContinue) { Stop-Process -Id $supervisor.ProcessId -Force }
}
Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
Write-Output 'Writing service stopped. Run files and backups remain in the private directory.'
