$ErrorActionPreference = 'Stop'
$repo = 'C:\aether-preview'
$preview = Join-Path $repo '.preview'
$old = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the current preview revision.' }
$fetch = Start-Process git -ArgumentList @('-C', $repo, 'fetch', 'origin', 'ui-glorious') -PassThru -NoNewWindow
if (-not $fetch.WaitForExit(180000)) { $fetch.Kill(); throw 'git fetch timed out after 180 seconds.' }
if ($fetch.ExitCode -ne 0) { throw 'git fetch failed.' }
$files = @(& git -C $repo diff --name-only $old origin/ui-glorious)
if ($LASTEXITCODE -ne 0) { throw 'Cannot compare preview revisions.' }
$apiChanged = @($files | Where-Object { $_ -match '^(control-api/|common/|compiler/|reference/|engine/|simulator/|infra/docker/control-api\.Dockerfile$|gradle/|gradlew$|settings\.gradle\.kts$|build\.gradle\.kts$)' }).Count -gt 0
$reset = Start-Process git -ArgumentList @('-C', $repo, 'reset', '--hard', 'origin/ui-glorious') -PassThru -NoNewWindow
if (-not $reset.WaitForExit(60000)) { $reset.Kill(); throw 'git reset timed out after 60 seconds.' }
if ($reset.ExitCode -ne 0) { throw 'git reset failed.' }
$compose = @('compose', '-p', 'aether-preview', '-f', 'docker-compose.yml', '-f', (Join-Path $preview 'compose.yml'), 'up', '-d', '--build', '--no-deps', '--wait', '--wait-timeout', '240')
if ($apiChanged) { $compose += @('control-api', 'web') } else { $compose += 'web' }
$p = Start-Process docker -ArgumentList $compose -WorkingDirectory $repo -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $preview 'refresh.out.log') -RedirectStandardError (Join-Path $preview 'refresh.err.log')
if (-not $p.WaitForExit(270000)) { $p.Kill(); throw 'Docker refresh timed out after 270 seconds.' }
if ($p.ExitCode -ne 0) { throw "Docker refresh failed ($($p.ExitCode)); see $preview\refresh.out.log and refresh.err.log" }
Write-Output "Refreshed to $((& git -C $repo rev-parse --short HEAD).Trim()); control-api rebuilt: $apiChanged"
