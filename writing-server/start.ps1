param([switch]$Tailnet,[switch]$Lan)
$ErrorActionPreference='Stop'
if($Tailnet -and $Lan){throw 'Choose either LAN or Tailscale mode.'}
$taskData=Join-Path $env:LOCALAPPDATA 'PocketTavernServer'
$taskMain=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'src\main.mjs'))
$taskNode=(Get-Command node -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $taskData -Force | Out-Null
$taskSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $taskData /inheritance:r /grant:r "*${taskSid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Unable to protect the private server directory.'}
$taskPidFile=Join-Path $taskData 'server.pid'
$taskRunning=$false
if(Test-Path -LiteralPath $taskPidFile){
  $taskOldId=0
  if([int]::TryParse([IO.File]::ReadAllText($taskPidFile).Trim(),[ref]$taskOldId)){
    $taskOld=Get-CimInstance Win32_Process -Filter "ProcessId=$taskOldId" -ErrorAction SilentlyContinue
    if($taskOld -and $taskOld.CommandLine.Contains($taskMain)){$taskRunning=$true}
  }
}
if(!$taskRunning){
  if($Lan){
    $taskLanAddress=Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address.IPAddress -match '^(10\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)' } | Select-Object -First 1 -ExpandProperty IPv4Address | Select-Object -First 1 -ExpandProperty IPAddress
    if(!$taskLanAddress){throw 'No active private IPv4 LAN address with a gateway was found.'}
    $taskConfigFile=Join-Path $taskData 'config.json'
    if(!(Test-Path -LiteralPath $taskConfigFile)){Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.example.json') -Destination $taskConfigFile}
    $taskLanConfig=Get-Content -LiteralPath $taskConfigFile -Raw | ConvertFrom-Json
    $taskLanConfig.host=$taskLanAddress
    $taskLanConfig | Add-Member -NotePropertyName lanMode -NotePropertyValue $true -Force
    [IO.File]::WriteAllText($taskConfigFile,($taskLanConfig | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
  }
  $env:POCKET_WRITING_DATA_DIR=$taskData
  $taskUserKey=[Environment]::GetEnvironmentVariable('VENICE_API_KEY','User')
  if(!$env:VENICE_API_KEY -and $taskUserKey){$env:VENICE_API_KEY=$taskUserKey}
  $taskProcess=Start-Process -FilePath $taskNode -ArgumentList @('"'+$taskMain+'"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskData 'server.stdout.log') -RedirectStandardError (Join-Path $taskData 'server.stderr.log')
  $taskReady=$false
  for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++){
    Start-Sleep -Milliseconds 300
    if($taskProcess.HasExited){throw 'Writing service exited. See private server.stderr.log.'}
    if(Test-Path -LiteralPath (Join-Path $taskData 'config.json')){
      $taskConfig=Get-Content -LiteralPath (Join-Path $taskData 'config.json') -Raw | ConvertFrom-Json
      try{$taskHealth=Invoke-RestMethod -Uri "http://$($taskConfig.host):$($taskConfig.port)/healthz" -TimeoutSec 2; $taskReady=$taskHealth.ok -eq $true}catch{}
      if($taskReady){break}
    }
  }
  if(!$taskReady){throw 'Writing service did not become healthy. See private logs.'}
}
$taskConfig=Get-Content -LiteralPath (Join-Path $taskData 'config.json') -Raw | ConvertFrom-Json
$taskUrl=''
if($Lan -or $taskConfig.lanMode -eq $true){
  if($taskConfig.lanMode -ne $true){throw 'Stop the idle service, then start with -Lan to change listening mode.'}
  $taskRuleName='PocketTavernWritingLan'
  $taskRule=Get-NetFirewallRule -Name $taskRuleName -ErrorAction SilentlyContinue
  if($taskRule){Remove-NetFirewallRule -Name $taskRuleName}
  New-NetFirewallRule -Name $taskRuleName -DisplayName 'Pocket Tavern writing server (local subnet only)' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $taskConfig.host -LocalPort $taskConfig.port -RemoteAddress LocalSubnet -Profile Any -Program $taskNode | Out-Null
  $taskUrl="http://$($taskConfig.host):$($taskConfig.port)/v1"
}
if($Tailnet){
  if($taskConfig.host -ne '127.0.0.1'){throw 'Tailscale mode requires loopback host 127.0.0.1 in config.json; restart the idle service after changing it.'}
  $taskTs=(& tailscale status --json | ConvertFrom-Json)
  if($LASTEXITCODE -ne 0 -or $taskTs.BackendState -ne 'Running'){throw 'Tailscale must be signed in on this PC.'}
  $taskExisting=(& tailscale serve status --json | ConvertFrom-Json)
  if($taskExisting.TCP.'8443' -or $taskExisting.Web.PSObject.Properties.Name -match ':8443$'){
    $taskSerialized=$taskExisting | ConvertTo-Json -Depth 20
    if(!$taskSerialized.Contains("http://127.0.0.1:$($taskConfig.port)")){throw 'Tailscale port 8443 already has another service; no configuration changed.'}
  }
  & tailscale serve --bg --https=8443 "http://127.0.0.1:$($taskConfig.port)"
  if($LASTEXITCODE -ne 0){throw 'Tailscale HTTPS setup needs attention. Local server remains available.'}
  $taskUrl="https://$($taskTs.Self.DNSName.TrimEnd('.')):8443/v1"
}
$taskToken=[IO.File]::ReadAllText((Join-Path $taskData 'gateway-token.txt')).Trim()
$taskConnection=@"
口袋酒馆电脑写作服务
平台类型：本机写作服务（OpenAI-compatible）
Base URL：$taskUrl
API Key（本机网关 Token，不是 Venice Key）：$taskToken
模型：pocket-direct（正常聊天） / pocket-writer（试验性审校建议，默认保留原稿）
局域网地址：手机连接与电脑相同的局域网；HTTPS Tailscale 地址：两端登录同一 Tailscale 网络。电脑需开机。
此文件包含私密 Token，请勿公开。
"@
[IO.File]::WriteAllText((Join-Path $taskData '手机连接.txt'),$taskConnection,[Text.UTF8Encoding]::new($false))
Write-Output "Local health: http://$($taskConfig.host):$($taskConfig.port)/healthz"
if($taskUrl){Write-Output "Phone Base URL: $taskUrl"}
Write-Output "Private connection instructions: $(Join-Path $taskData '手机连接.txt')"
