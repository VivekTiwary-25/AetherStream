$ErrorActionPreference = 'Stop'
$repo = 'C:\aether-preview'
$preview = Join-Path $repo '.preview'
$pidFile = Join-Path $preview 'cloudflared.pid'
if (Test-Path $pidFile) {
    $id = Get-Content $pidFile -ErrorAction SilentlyContinue
    if ($id -and (Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue)) { Stop-Process -Id ([int]$id) -Force }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}
if (Test-Path $repo) {
    $out = Join-Path $preview 'down.out.log'
    $err = Join-Path $preview 'down.err.log'
    $p = Start-Process docker -ArgumentList @('compose', '-p', 'aether-preview', '-f', 'docker-compose.yml', '-f', (Join-Path $preview 'compose.yml'), 'down') -WorkingDirectory $repo -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
    if (-not $p.WaitForExit(180000)) { $p.Kill(); throw 'docker compose down timed out after 180 seconds.' }
    if ($p.ExitCode -ne 0) { throw "docker compose down failed ($($p.ExitCode)); see $out and $err" }
}
