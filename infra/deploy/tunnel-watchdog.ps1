#requires -Version 5.1
[CmdletBinding()]
param([switch]$DryRun)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskName = 'AetherStream-Tunnel'
$logPath = 'C:/Users/Arena/aether-tunnel.log'
$urlPath = 'C:/Users/Arena/aether-public-url.txt'
$htmlPath = 'C:/Users/Arena/aether-link.html'
# Match only a quick-tunnel hostname, not a URL containing one as a prefix.
$urlPattern = 'https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com(?![a-z0-9.-])'

if ($DryRun) {
    Write-Output "DRY RUN: Every 60 s read the newest quick-tunnel URL from $logPath."
    Write-Output 'DRY RUN: GET <url>/api/version with a 10 s timeout; success resets consecutive failures.'
    Write-Output "DRY RUN: After 3 failures (including no URL), Stop-ScheduledTask -TaskName '$taskName' -TaskPath '\'; wait up to 30 s for it to stop; Start-ScheduledTask."
    Write-Output 'DRY RUN: Poll every 2 s for up to 120 s for a different URL; never republish the pre-restart URL while waiting.'
    Write-Output "DRY RUN: Publish each discovered URL to $urlPath and $htmlPath (64px clickable URL, no QR or external resources)."
    Write-Output 'DRY RUN: Exit without reading logs, network requests, task changes, file writes, or sleeps.'
    return
}

function Read-TunnelUrl {
    try {
        if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) { return $null }
        $text = Get-Content -LiteralPath $logPath -Raw
        if ([string]::IsNullOrEmpty($text)) { return $null }
        $match = [regex]::Match($text, $urlPattern, [System.Text.RegularExpressions.RegexOptions]::RightToLeft)
        if ($match.Success) { return $match.Value }
    } catch {
        Write-Warning "Cannot read tunnel log: $($_.Exception.Message)"
    }
    return $null
}

function Publish-TunnelUrl([string]$Url) {
    $escaped = [System.Net.WebUtility]::HtmlEncode($Url)
    $html = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="60"><title>AetherStream public link</title>
<style>body{font-family:system-ui,sans-serif;margin:3rem}a{font-size:64px;overflow-wrap:anywhere}</style></head>
<body><h1>AetherStream demo</h1><p><a href="$escaped">$escaped</a></p><p>Temporary public link. This page refreshes every 60 seconds.</p></body></html>
"@
    Set-Content -LiteralPath $urlPath -Value $Url -Encoding UTF8
    Set-Content -LiteralPath $htmlPath -Value $html -Encoding UTF8
    Write-Output "Public link: $Url"
}

function Restart-Tunnel {
    Stop-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
    # Stop is asynchronous; starting a still-running IgnoreNew task would do nothing.
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-ScheduledTask -TaskName $taskName -TaskPath '\').State -in @('Running', 'Queued')) {
        if ((Get-Date) -ge $deadline) { throw 'Tunnel task did not stop within 30 seconds.' }
        Start-Sleep -Seconds 1
    }
    Start-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
}

function Wait-NewTunnelUrl([string]$PreviousUrl) {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $candidate = Read-TunnelUrl
        if ($candidate -and $candidate -ne $PreviousUrl) { return $candidate }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Write-Warning 'No new tunnel URL within 120 seconds; continuing normal checks without republishing the old URL.'
    return $null
}

$failures = 0
$currentUrl = $null
$publishedUrl = $null
$awaitingNewUrl = $false
$previousUrl = $null
while ($true) {
    $url = Read-TunnelUrl
    if ($awaitingNewUrl -and $url -eq $previousUrl) { $url = $null }
    if ($url) {
        $currentUrl = $url
        $awaitingNewUrl = $false
        if ($publishedUrl -ne $url) {
            try {
                Publish-TunnelUrl $url
                $publishedUrl = $url
            } catch {
                Write-Warning "Cannot publish public link: $($_.Exception.Message)"
            }
        }
    }

    $healthy = $false
    if ($url) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$url/api/version" -TimeoutSec 10 -MaximumRedirection 0
            $healthy = $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
        } catch {
            Write-Warning "Public version check failed: $($_.Exception.Message)"
        }
    }
    if ($healthy) {
        $failures = 0
    } else {
        $failures++
        Write-Warning "Public link failure $failures/3 (URL missing, awaiting replacement, or GET failed)."
    }

    if ($failures -ge 3) {
        $failures = 0
        $previousUrl = $currentUrl
        $awaitingNewUrl = $true
        try {
            Write-Output "Restarting $taskName after three consecutive failures."
            Restart-Tunnel
            $newUrl = Wait-NewTunnelUrl $previousUrl
            if ($newUrl) {
                $currentUrl = $newUrl
                $awaitingNewUrl = $false
                Publish-TunnelUrl $newUrl
                $publishedUrl = $newUrl
            }
        } catch {
            Write-Warning "Tunnel recovery failed: $($_.Exception.Message)"
        }
    }
    Start-Sleep -Seconds 60
}
