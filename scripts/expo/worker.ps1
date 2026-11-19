param(
    [Parameter(Position = 0)][string]$Action,
    [Parameter(Position = 1)][string]$WorkerNumber
)

$ErrorActionPreference = 'Stop'
$base = if ($env:AETHER_URL) { $env:AETHER_URL.TrimEnd('/') } else { 'http://localhost:8088' }

try {
    if ($Action -eq 'status' -and -not $WorkerNumber) {
        $workers = Invoke-RestMethod -Method Get -Uri "$base/api/workers" -TimeoutSec 10
        foreach ($worker in $workers) {
            $partitions = if ($null -eq $worker.partitions) { '' } else { $worker.partitions -join ',' }
            Write-Output "$($worker.workerId): alive=$($worker.alive) state=$($worker.state) partitions=[$partitions]"
        }
        exit 0
    }
    if ($Action -ne 'kill' -or $WorkerNumber -notmatch '^[1-3]$') {
        [Console]::Error.WriteLine('Usage: worker kill <1..3> | worker status')
        exit 2
    }

    $body = @{ workerId = "worker-$WorkerNumber" } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$base/api/chaos/kill-worker" -ContentType 'application/json' -Body $body -TimeoutSec 10
    $id = if ($response.workerId) { $response.workerId } else { "worker-$WorkerNumber" }
    Write-Output "Killed $id (engine will recover; watch Settings → Logs & Recovery)"
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
