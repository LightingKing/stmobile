param([string]$KeyFile='')
$ErrorActionPreference='Stop'
if($PSHOME -like '*\WindowsApps\*'){throw 'This is the Microsoft Store PowerShell, which keeps AppData in its own package folder. Run the script with Windows PowerShell (powershell.exe) instead.'}
$taskData=Join-Path $env:LOCALAPPDATA 'PocketTavernServer'
New-Item -ItemType Directory -Path $taskData -Force | Out-Null
$taskSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $taskData /inheritance:r /grant:r "*${taskSid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Unable to protect the private server directory.'}
if($KeyFile){$taskKey=[IO.File]::ReadAllText([IO.Path]::GetFullPath($KeyFile)).Trim()}
else{
  $taskSecure=Read-Host 'Venice API Key (hidden input)' -AsSecureString
  $taskKey=[System.Net.NetworkCredential]::new('',$taskSecure).Password.Trim()
}
if(!$taskKey -or $taskKey -match '\s'){throw 'Key must be a nonempty single line.'}
[IO.File]::WriteAllText((Join-Path $taskData 'venice-key.txt'),$taskKey,[Text.UTF8Encoding]::new($false))
$taskKey=$null
Write-Output 'Venice Key saved privately. Restart the writing service when no generation is running.'
