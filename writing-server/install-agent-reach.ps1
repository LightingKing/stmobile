param([string]$Python = '')
# Installs Agent Reach (github.com/Panniantong/Agent-Reach, MIT) for the writing server only:
# a pinned commit in its own venv at runtime\agent-reach, with its home at runtime\agent-reach-home.
# Agent Reach's own "setup" is not run: it would add its Skill to every AI tool on this PC.
# Only the Bilibili helper is added; channels that need a browser login stay off.
$ErrorActionPreference = 'Stop'
$commit = 'c70ae6da5d39bc90e09ad2ae0575ccc89f31e284'  # 1.6.1, reviewed 2026-11-08
$runtime = Join-Path $PSScriptRoot 'runtime'
$venv = Join-Path $runtime 'agent-reach'
$home_ = Join-Path $runtime 'agent-reach-home'

if (!$Python) {
  $Python = (Get-Command python -ErrorAction SilentlyContinue).Source
  if (!$Python) { throw 'Python 3.10+ not found; pass -Python <path to python.exe>' }
}
$version = & $Python -c 'import sys; print("%d.%d" % sys.version_info[:2])'
if ([version]$version -lt [version]'3.10') { throw "Python $version is too old; Agent Reach needs 3.10+" }

New-Item -ItemType Directory -Force -Path $runtime, $home_ | Out-Null
if (!(Test-Path (Join-Path $venv 'Scripts\python.exe'))) { & $Python -m venv $venv }
$py = Join-Path $venv 'Scripts\python.exe'
# The PyPI package named agent-reach is a different project: install from GitHub at the pinned commit.
& $py -m pip install --disable-pip-version-check --upgrade "agent-reach @ git+https://github.com/Panniantong/Agent-Reach@$commit" `
  'requests==2.32.5' 'loguru==0.7.3' 'PyYAML==6.0.3' 'yt-dlp==2026.8.19' 'yt-dlp-ejs==0.8.0'
if ($LASTEXITCODE) { throw 'pip install failed' }

$env:HOME = $home_
$exe = Join-Path $venv 'Scripts\agent-reach.exe'
& $exe add bilibili | Out-Null
& $exe version
