$ErrorActionPreference = 'Stop'
$repo = 'C:\aether-preview'
$preview = Join-Path $repo '.preview'
$deployed = Join-Path $preview 'deployed-revision.txt'

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds)
    $out = Join-Path $preview 'refresh-command.out.log'
    $err = Join-Path $preview 'refresh-command.err.log'
    $p = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $repo -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle  # PS 5.1: cache the handle so ExitCode is not $null after WaitForExit(timeout)
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        $p.Kill()
        throw "$File timed out after $TimeoutSeconds seconds; see $out and $err"
    }
    if ($p.ExitCode -ne 0) { throw "$File failed ($($p.ExitCode)); see $out and $err" }
    if (Test-Path $out) { Get-Content $out }
}

$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'Never'
$old = (Invoke-Checked 'git' @('-C', $repo, 'rev-parse', 'HEAD') 30).Trim()
if (Test-Path $deployed) {
    $baseline = (Get-Content $deployed -Raw).Trim()
} else {
    $baseline = $old
    $baseline | Set-Content -Encoding ascii $deployed
}
$null = Invoke-Checked 'git' @('-C', $repo, 'fetch', 'origin', 'ui-glorious') 180
$target = (Invoke-Checked 'git' @('-C', $repo, 'rev-parse', 'origin/ui-glorious') 30).Trim()
$files = @(Invoke-Checked 'git' @('-C', $repo, 'diff', '--name-only', $baseline, $target) 60)
$apiChanged = @($files | Where-Object { $_ -match '^(control-api/|common/|compiler/|reference/|engine/|simulator/|infra/docker/control-api\.Dockerfile$|gradle/|gradlew$|settings\.gradle\.kts$|build\.gradle\.kts$)' }).Count -gt 0
$null = Invoke-Checked 'git' @('-C', $repo, 'reset', '--hard', $target) 60
$composeBase = @('compose', '-p', 'aether-preview', '-f', 'docker-compose.yml', '-f', (Join-Path $preview 'compose.yml'))
if ($apiChanged) { $services = @('control-api', 'web') } else { $services = @('web') }
$null = Invoke-Checked 'docker' ($composeBase + @('build') + $services) 270
$null = Invoke-Checked 'docker' ($composeBase + @('up', '-d', '--no-build', '--no-deps', '--wait', '--wait-timeout', '240') + $services) 270
$target | Set-Content -Encoding ascii $deployed
$revision = (Invoke-Checked 'git' @('-C', $repo, 'rev-parse', '--short', 'HEAD') 30).Trim()
Write-Output "Refreshed to $revision; control-api rebuilt: $apiChanged"
