# Shared helpers for the writing-server scripts. Works in Windows PowerShell 5.1 and PowerShell 7.
# Saved as UTF-8 with BOM so Windows PowerShell on a Chinese system reads the Chinese text correctly.

$PocketTaskName = 'PocketTavernWritingServer'
$PocketFirewallRule = 'PocketTavernWritingLan'
$PocketRoot = Split-Path -Parent $PSScriptRoot
$PocketMain = [IO.Path]::GetFullPath((Join-Path $PocketRoot 'src\main.mjs'))
$PocketRunScript = [IO.Path]::GetFullPath((Join-Path $PocketRoot 'run.ps1'))
# The Store build of PowerShell 7 redirects AppData writes into its own package folder, so the
# server and its task always run under the Windows PowerShell that ships with the system.
$PocketWindowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if ($PSHOME -like '*\WindowsApps\*') { throw 'This is the Microsoft Store PowerShell, which keeps AppData in its own package folder. Run the script with Windows PowerShell (powershell.exe) instead.' }

function Get-PocketDataDir { Join-Path $env:LOCALAPPDATA 'PocketTavernServer' }

function Initialize-PocketDataDir {
  $dir = Get-PocketDataDir
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  & icacls.exe $dir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Unable to protect the private server directory.' }
  $config = Join-Path $dir 'config.json'
  if (!(Test-Path -LiteralPath $config)) { Copy-Item -LiteralPath (Join-Path $PocketRoot 'config.example.json') -Destination $config }
  $dir
}

function Get-PocketNode { (Get-Command node -ErrorAction Stop).Source }

function Read-PocketConfig([string]$Dir) {
  Get-Content -LiteralPath (Join-Path $Dir 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Write-PocketLog([string]$File, [string]$Message) {
  Add-Content -LiteralPath $File -Value "$(Get-Date -Format s) $Message" -Encoding UTF8
}

function Test-PocketAdmin {
  ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-PocketLanConfiguration {
  Get-NetIPConfiguration | Where-Object {
    $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and
    ($_.IPv4Address.IPAddress | Where-Object { $_ -match '^(10\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)' })
  } | Select-Object -First 1
}

function Get-PocketLanAddress {
  $lan = Get-PocketLanConfiguration
  if ($lan) { $lan.IPv4Address.IPAddress | Where-Object { $_ -match '^(10\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)' } | Select-Object -First 1 }
}

# Points config.json at the current LAN address so a DHCP change only needs a restart.
function Set-PocketLanHost([string]$Dir) {
  $address = Get-PocketLanAddress
  if (!$address) { throw 'No active private IPv4 LAN address with a gateway was found.' }
  $config = Read-PocketConfig $Dir
  if ($config.host -ne $address -or $config.lanMode -ne $true) {
    $config | Add-Member -NotePropertyName host -NotePropertyValue $address -Force
    $config | Add-Member -NotePropertyName lanMode -NotePropertyValue $true -Force
    [IO.File]::WriteAllText((Join-Path $Dir 'config.json'), ($config | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
  }
  $address
}

# Allows phones on the local subnet to reach the port. Not tied to one address, so DHCP changes
# keep working. Only the Private profile unless the LAN is knowingly left as a Public network.
function Set-PocketFirewallRule([int]$Port, [switch]$AllowPublicNetwork) {
  if (!(Test-PocketAdmin)) { Write-Warning 'Not elevated: the firewall rule was not changed. Run this script as administrator once to let phones on the LAN connect.'; return }
  Get-NetFirewallRule -Name $PocketFirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  $profiles = if ($AllowPublicNetwork) { @('Private', 'Public') } else { @('Private') }
  New-NetFirewallRule -Name $PocketFirewallRule -DisplayName 'Pocket Tavern writing server (local subnet only)' -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet -Profile $profiles -Program (Get-PocketNode) | Out-Null
  $lan = Get-PocketLanConfiguration
  if ($lan -and !$AllowPublicNetwork) {
    $category = (Get-NetConnectionProfile -InterfaceIndex $lan.InterfaceIndex -ErrorAction SilentlyContinue).NetworkCategory
    if ("$category" -eq 'Public') {
      Write-Warning ("Windows treats the LAN '" + $lan.InterfaceAlias + "' as a Public network, so phones on it stay blocked. " +
        "If this is your home network, run as administrator: Set-NetConnectionProfile -InterfaceIndex " + $lan.InterfaceIndex + " -NetworkCategory Private")
    }
  }
}

function Get-PocketProcess([string]$PidFile, [string]$CommandPart) {
  if (!(Test-Path -LiteralPath $PidFile)) { return $null }
  $id = 0
  if (![int]::TryParse([IO.File]::ReadAllText($PidFile).Trim(), [ref]$id)) { return $null }
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
  if ($process -and $process.CommandLine -and $process.CommandLine.Contains($CommandPart)) { $process } else { $null }
}

function Get-PocketServerProcess([string]$Dir) { Get-PocketProcess (Join-Path $Dir 'server.pid') $PocketMain }
function Get-PocketSupervisorProcess([string]$Dir) { Get-PocketProcess (Join-Path $Dir 'supervisor.pid') $PocketRunScript }

function Test-PocketHealth([int]$Port) {
  $client = New-Object Net.WebClient
  $client.Proxy = $null
  try { ($client.DownloadString("http://127.0.0.1:$Port/healthz") | ConvertFrom-Json).ok -eq $true } catch { $false } finally { $client.Dispose() }
}

function Wait-PocketHealth([int]$Port, [int]$Seconds = 30) {
  for ($i = 0; $i -lt $Seconds * 2; $i++) {
    if (Test-PocketHealth $Port) { return $true }
    Start-Sleep -Milliseconds 500
  }
  $false
}

# The private connection note for the phone; it holds the gateway token, never the Venice key.
function Write-PocketConnectionFile([string]$Dir, [string[]]$Urls) {
  $token = [IO.File]::ReadAllText((Join-Path $Dir 'gateway-token.txt')).Trim()
  $lines = @(
    '口袋酒馆电脑写作服务'
    '平台类型：本机写作服务（OpenAI-compatible）'
  )
  if ($Urls.Count -eq 0) { $lines += 'Base URL：（尚未开启局域网或远程访问）' }
  foreach ($url in $Urls) { $lines += "Base URL：$url" }
  $lines += @(
    "API Key（本机网关 Token，不是 Venice Key）：$token"
    '模型：pocket-direct（正常聊天） / pocket-writer（试验性审校建议，默认保留原稿）'
    '局域网地址：手机连接与电脑相同的局域网；HTTPS 地址：经 Tailscale 或 NPS 域名访问。电脑需开机。'
    '此文件包含私密 Token，请勿公开。'
  )
  [IO.File]::WriteAllText((Join-Path $Dir '手机连接.txt'), ($lines -join "`r`n"), [Text.UTF8Encoding]::new($false))
  $token = $null
}
