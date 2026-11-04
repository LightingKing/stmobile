param([switch]$Lan)
# Runs the writing server in the foreground and restarts it after a crash. The logon task
# (install-autostart.ps1) and start.ps1 launch this; stop.ps1 ends it through stop.request.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\windows-common.ps1')
$dir = Initialize-PocketDataDir
$running = Get-PocketSupervisorProcess $dir
if ($running -and $running.ProcessId -ne $PID) { exit 0 }
$supervisorPid = Join-Path $dir 'supervisor.pid'
$stopFile = Join-Path $dir 'stop.request'
$log = Join-Path $dir 'supervisor.log'
[IO.File]::WriteAllText($supervisorPid, "$PID")
Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
$env:POCKET_WRITING_DATA_DIR = $dir
$env:POCKET_SUPERVISOR_PID = "$PID"
$userKey = [Environment]::GetEnvironmentVariable('VENICE_API_KEY', 'User')
if (!$env:VENICE_API_KEY -and $userKey) { $env:VENICE_API_KEY = $userKey }
$node = Get-PocketNode
$delay = 5
try {
  while ($true) {
    if ($Lan) {
      try { Set-PocketLanHost $dir | Out-Null }
      catch { Write-PocketLog $log "LAN address unavailable: $($_.Exception.Message)" }
    }
    $started = Get-Date
    & $node $PocketMain
    $code = $LASTEXITCODE
    if (Test-Path -LiteralPath $stopFile) { break }
    if (((Get-Date) - $started).TotalMinutes -ge 10) { $delay = 5 }
    Write-PocketLog $log "Server exited with code $code; restarting in $delay s."
    for ($i = 0; $i -lt $delay -and !(Test-Path -LiteralPath $stopFile); $i++) { Start-Sleep -Seconds 1 }
    if (Test-Path -LiteralPath $stopFile) { break }
    $delay = [Math]::Min($delay * 2, 60)
  }
} finally {
  Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $supervisorPid -ErrorAction SilentlyContinue
}
