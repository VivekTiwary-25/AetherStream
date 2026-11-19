param(
    [Parameter(Position = 0)][string]$Action,
    [Parameter(Position = 1)][string]$Count = '20'
)

$ErrorActionPreference = 'Stop'
$base = if ($env:AETHER_URL) { $env:AETHER_URL.TrimEnd('/') } else { 'http://localhost:8088' }

if ($Action -ne 'late' -or $Count -notmatch '^[1-9][0-9]*$') {
    [Console]::Error.WriteLine('Usage: events late [count] (count must be a positive integer)')
    exit 2
}
try {
    $body = @{ count = [int]$Count; lateByMs = 180000 } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$base/api/chaos/late-events" -ContentType 'application/json' -Body $body -TimeoutSec 10 | Out-Null
    Write-Output "Sent $Count late events (they appear in Analytics -> Failed Records if the engine classifies them late)"
} catch {
    $message = $_.ErrorDetails.Message
    if (-not $message) { $message = $_.Exception.Message }
    try {
        $parsed = $message | ConvertFrom-Json
        if ($parsed.message) { $message = $parsed.message }
        elseif ($parsed.error) { $message = $parsed.error }
    } catch { }
    [Console]::Error.WriteLine($message)
    exit 1
}
