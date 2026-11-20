$ErrorActionPreference = 'Stop'
$repo = 'C:\aether-preview'
$preview = Join-Path $repo '.preview'
$override = Join-Path $preview 'compose.yml'
$logOut = Join-Path $preview 'cloudflared.out.log'
$logErr = Join-Path $preview 'cloudflared.err.log'

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds = 300)
    $logDir = if (Test-Path $preview) { $preview } else { $env:TEMP }
    $out = Join-Path $logDir 'aether-preview-command.out.log'
    $err = Join-Path $logDir 'aether-preview-command.err.log'
    $working = if (Test-Path $repo) { $repo } else { $env:TEMP }
    $p = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $working -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle  # PS 5.1: cache the handle so ExitCode is not $null after WaitForExit(timeout)
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        $p.Kill()
        throw "$File timed out after $TimeoutSeconds seconds; see $out and $err"
    }
    if ($p.ExitCode -ne 0) { throw "$File failed ($($p.ExitCode)); see $out and $err" }
    if (Test-Path $out) { Get-Content $out }
    if (Test-Path $err) { Get-Content $err }
}

if (-not (Test-Path $repo)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $repo) | Out-Null
    Invoke-Checked 'git' @('clone', '--branch', 'ui-glorious', 'https://github.com/VivekTiwary-25/AetherStream.git', $repo) 300
} else {
    Invoke-Checked 'git' @('fetch', 'origin', 'ui-glorious') 180
    Invoke-Checked 'git' @('reset', '--hard', 'origin/ui-glorious') 60
}
New-Item -ItemType Directory -Force -Path $preview | Out-Null
$upScript = Join-Path $preview 'preview-up.ps1'
if ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'preview-up.ps1')) -ne [IO.Path]::GetFullPath($upScript)) {
    Copy-Item (Join-Path $PSScriptRoot 'preview-up.ps1') $upScript -Force
}
Copy-Item (Join-Path $PSScriptRoot 'preview-refresh.ps1') (Join-Path $preview 'preview-refresh.ps1') -Force
Copy-Item (Join-Path $PSScriptRoot 'preview-down.ps1') (Join-Path $preview 'preview-down.ps1') -Force
if (Test-Path (Join-Path $PSScriptRoot 'README.md')) {
    Copy-Item (Join-Path $PSScriptRoot 'README.md') (Join-Path $preview 'README.md') -Force
}
@'
services:
  web:
    ports:
      - "8089:80"
'@ | Set-Content -Encoding ascii $override

Invoke-Checked 'docker' @('compose', '-p', 'aether-preview', '-f', 'docker-compose.yml', '-f', $override, 'up', '-d', '--build', '--wait', '--wait-timeout', '600') 1200

$cloudflared = Join-Path $preview 'cloudflared.exe'
if (-not (Get-Command cloudflared.exe -ErrorAction SilentlyContinue)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $cloudflared -TimeoutSec 180
} else {
    $cloudflared = (Get-Command cloudflared.exe).Source
}
Remove-Item $logOut, $logErr -Force -ErrorAction SilentlyContinue
$tunnel = Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--no-autoupdate', '--url', 'http://localhost:8089') -WorkingDirectory $preview -PassThru -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
$tunnel.Id | Set-Content -Encoding ascii (Join-Path $preview 'cloudflared.pid')
$url = $null
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline -and -not $url) {
    if ($tunnel.HasExited) { throw "cloudflared exited; see $logErr" }
    foreach ($log in @($logOut, $logErr)) {
        if (Test-Path $log) {
            $match = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1
            if ($match) { $url = $match.Matches[0].Value; break }
        }
    }
    if (-not $url) { Start-Sleep -Seconds 2 }
}
if (-not $url) { Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue; throw "Tunnel URL not found within 90 seconds; see $logOut and $logErr" }
$url | Set-Content -Encoding ascii (Join-Path $preview 'url.txt')
Write-Output "Preview: $url"
Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8089/' -TimeoutSec 15 | Select-Object StatusCode
Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8089/api/health' -TimeoutSec 15 | Select-Object StatusCode
