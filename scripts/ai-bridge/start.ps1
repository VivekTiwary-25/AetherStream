$ErrorActionPreference = 'Stop'
$runDir = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
if (-not $env:AI_BIND) { $env:AI_BIND = '127.0.0.1' }
if (-not $env:AI_PORT) { $env:AI_PORT = '18090' }
if (-not $env:CLAUDE_CONFIG_DIR) {
    $profileDir = Join-Path $env:USERPROFILE '.swarm\profiles\vachana-claude\claude'
    if (Test-Path -LiteralPath $profileDir -PathType Container) { $env:CLAUDE_CONFIG_DIR = $profileDir }
}
$python = (Get-Command python -ErrorAction Stop).Source
$log = Join-Path $runDir 'bridge.log'
$err = Join-Path $runDir 'bridge.err.log'
$scriptPath = Join-Path $PSScriptRoot 'ai_bridge.py'
$process = Start-Process -FilePath $python -ArgumentList ('"{0}"' -f $scriptPath) -WorkingDirectory $PSScriptRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err
Set-Content -LiteralPath (Join-Path $runDir 'bridge.pid') -Value $process.Id -NoNewline
Write-Output "AI bridge started (PID $($process.Id)); logs: $runDir"
