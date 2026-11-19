#requires -Version 5.1
[CmdletBinding()]
param([switch]$DryRun)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$logPath = 'C:/Users/Arena/aether-autostart.log'
$bashPath = 'C:/Users/Arena/AppData/Local/Programs/Git/bin/bash.exe'
$demoUrl = 'http://localhost:8088'
$composeArgs = @('compose', '-p', 'aether', '-f', 'docker-compose.yml', '-f', 'infra/deploy/docker-compose.deploy.yml', '--profile', 'monitoring')
$offlineFile = Join-Path $repoRoot 'docker-compose.offline.yml'
$tagFile = Join-Path $PSScriptRoot '.last-deployed'

function Write-Log([string]$Message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss.fffK'), $Message
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

function Format-Argument([string]$Value) {
    # Windows native argv quoting, including paths with spaces and trailing slashes.
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    return '"' + [regex]::Replace($escaped, '(\\+)$', '$1$1') + '"'
}

function Invoke-LoggedCommand([string]$FilePath, [string[]]$CommandArgs, [int]$TimeoutSeconds) {
    $arguments = ($CommandArgs | ForEach-Object { Format-Argument $_ }) -join ' '
    Write-Log ('> {0} {1}' -f (Format-Argument $FilePath), $arguments)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $FilePath
    $process.StartInfo.Arguments = $arguments
    $process.StartInfo.WorkingDirectory = $repoRoot
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        if (-not $process.Start()) { throw "Could not start $FilePath" }
        # Drain both pipes asynchronously so verbose native tools cannot deadlock.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut) {
            $process.Kill()
            $process.WaitForExit()
        }
        $lines = @()
        foreach ($text in @($stdout.GetAwaiter().GetResult(), $stderr.GetAwaiter().GetResult())) {
            foreach ($line in ($text -split '\r?\n')) {
                if ($line -ne '') {
                    Write-Log $line
                    $lines += $line
                }
            }
        }
        if ($timedOut) { throw "$FilePath exceeded ${TimeoutSeconds}s" }
        Write-Log ("Exit code: {0}" -f $process.ExitCode)
        return @{ ExitCode = $process.ExitCode; Lines = $lines }
    } finally {
        $process.Dispose()
    }
}

function Show-Result([string]$Message) {
    # An interactive scheduled task can show this console without toast registration.
    $quoted = $Message.Replace("'", "''")
    $code = "`$Host.UI.RawUI.WindowTitle = 'AetherStream Expo'; Write-Host '$quoted'; Write-Host '$demoUrl'; Write-Host 'Log: $logPath'"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    Start-Process -FilePath "$PSHOME/powershell.exe" -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) | Out-Null
}

if ($DryRun) {
    Write-Output "DRY RUN: working directory = $repoRoot"
    Write-Output "Log each command, output line and exit code with timestamps to $logPath (not written in DryRun)."
    Write-Output 'Wait up to 300s: docker info (each attempt bounded to 10s, retry every 5s).'
    Write-Output 'Set AETHER_TAG from the environment, otherwise infra/deploy/.last-deployed; fail if missing or not a 7-64 digit hex SHA.'
    Write-Output 'Unless AETHER_OFFLINE=1: HEAD https://ghcr.io/v2/ (5s timeout; any HTTP response means online).'
    Write-Output 'If AETHER_OFFLINE=1 or no internet, append docker-compose.offline.yml when present; disable pulls and builds even if absent.'
    $normal = 'docker ' + ($composeArgs -join ' ')
    Write-Output "Online: $normal up -d --no-build --wait --wait-timeout 180"
    Write-Output "Offline, override present: $normal -f docker-compose.offline.yml up -d --no-build --pull never --wait --wait-timeout 180"
    Write-Output "Offline, override absent: $normal up -d --no-build --pull never --wait --wait-timeout 180"
    Write-Output 'Abort on Compose failure or health timeout (requires Compose with --wait support).'
    Write-Output 'Set EXPECT_SHA=$env:AETHER_TAG for the readiness check.'
    Write-Output "& '$bashPath' --noprofile --norc infra/scripts/demo-ready.sh $demoUrl"
    Write-Output "On exit 0 with a 'DEMO READY ...' line: show that line and $demoUrl in a new PowerShell console; otherwise show DEMO FAILED and exit 1."
    return
}

try {
    Write-Log "Starting Expo autostart in $repoRoot"
    if (-not (Test-Path -LiteralPath $bashPath -PathType Leaf)) { throw "Git Bash missing: $bashPath" }
    $dockerPath = (Get-Command docker.exe -CommandType Application -ErrorAction Stop).Source
    $deadline = [DateTime]::UtcNow.AddSeconds(300)
    $dockerReady = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $remaining = [int][Math]::Floor(($deadline - [DateTime]::UtcNow).TotalSeconds)
        if ($remaining -lt 1) { break }
        try {
            $probe = Invoke-LoggedCommand $dockerPath @('info') ([Math]::Min(10, $remaining))
            if ($probe.ExitCode -eq 0) { $dockerReady = $true; break }
        } catch {
            Write-Log $_.Exception.Message
        }
        $remaining = [int][Math]::Floor(($deadline - [DateTime]::UtcNow).TotalSeconds)
        if ($remaining -gt 0) { Start-Sleep -Seconds ([Math]::Min(5, $remaining)) }
    }
    if (-not $dockerReady) { throw 'Docker did not become ready within 300s; check Docker Desktop.' }

    $tag = $env:AETHER_TAG
    if ([string]::IsNullOrWhiteSpace($tag) -and (Test-Path -LiteralPath $tagFile -PathType Leaf)) {
        $tag = (Get-Content -LiteralPath $tagFile -Raw).Trim()
    }
    if ($tag -notmatch '^[a-fA-F0-9]{7,64}$') {
        throw 'Set AETHER_TAG to a published CD commit SHA or deploy once to create infra/deploy/.last-deployed.'
    }
    $env:AETHER_TAG = $tag
    Write-Log "Using CD image SHA $tag"

    $offline = $env:AETHER_OFFLINE -eq '1'
    if (-not $offline) {
        Write-Log 'Checking internet: HEAD https://ghcr.io/v2/ (5s timeout)'
        try {
            $null = Invoke-WebRequest -Uri 'https://ghcr.io/v2/' -Method Head -UseBasicParsing -TimeoutSec 5
        } catch {
            # GHCR returns 401 without authentication; an HTTP response still proves connectivity.
            $offline = $null -eq $_.Exception.Response
            Write-Log ("Internet probe: {0}" -f $_.Exception.Message)
        }
    }
    $upArgs = @('up', '-d', '--no-build')
    if ($offline) {
        if (Test-Path -LiteralPath $offlineFile -PathType Leaf) {
            $composeArgs += @('-f', 'docker-compose.offline.yml')
            Write-Log 'Offline: applying docker-compose.offline.yml after the CD override.'
        } else {
            Write-Log 'Offline override absent: using cached CD images only.'
        }
        $upArgs += @('--pull', 'never')
    } else {
        Write-Log 'Online: using the normal CD Compose files and pull policies.'
    }
    $upArgs += @('--wait', '--wait-timeout', '180')
    $up = Invoke-LoggedCommand $dockerPath ($composeArgs + $upArgs) 300
    if ($up.ExitCode -ne 0) { throw "Compose startup/health check failed (exit $($up.ExitCode))." }

    $env:EXPECT_SHA = $tag
    $demo = Invoke-LoggedCommand $bashPath @('--noprofile', '--norc', 'infra/scripts/demo-ready.sh', $demoUrl) 900
    if ($demo.ExitCode -ne 0) { throw "demo-ready.sh failed (exit $($demo.ExitCode))." }
    $readyLines = @($demo.Lines | Where-Object { $_ -match '^DEMO READY ' })
    if ($readyLines.Count -eq 0) { throw 'demo-ready.sh exited without a DEMO READY line.' }
    $finalLine = $readyLines[-1]
    Write-Log "$finalLine $demoUrl"
    Show-Result $finalLine
} catch {
    $failure = 'DEMO FAILED: ' + $_.Exception.Message
    Write-Host $failure
    try { Write-Log $failure } catch { Write-Warning "Cannot write log: $logPath" }
    try { Show-Result $failure } catch { Write-Warning $_.Exception.Message }
    exit 1
}
