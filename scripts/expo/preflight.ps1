$ErrorActionPreference = 'Stop'
$base = if ($env:AETHER_URL) { $env:AETHER_URL.TrimEnd('/') } else { 'http://localhost:8088' }
$script:failed = $false

function Invoke-Check {
    param([string]$Name, [scriptblock]$Check, [switch]$Warn)
    try {
        $reason = & $Check
        if ($reason -is [System.Collections.IDictionary] -and $reason.Warn) {
            Write-Output "WARN $Name - $($reason.Reason)"
        } else {
            Write-Output "PASS $Name - $reason"
        }
    } catch {
        if ($Warn) {
            Write-Output "WARN $Name - $($_.Exception.Message)"
        } else {
            $script:failed = $true
            Write-Output "FAIL $Name - $($_.Exception.Message)"
        }
    }
}

function Get-Json {
    param([string]$Path)
    return Invoke-RestMethod -Method Get -Uri "$base$Path" -TimeoutSec 10
}

Invoke-Check 'web /' {
    $page = Invoke-WebRequest -Method Get -Uri "$base/" -TimeoutSec 10 -UseBasicParsing
    if ($page.Content -notmatch '(?is)<html|<!doctype\s+html') { throw 'response is not HTML' }
    'HTML returned'
}
Invoke-Check '/api/health' {
    $health = Get-Json '/api/health'
    $state = [string]$health.status
    if ($state -notin @('UP', 'OK')) { throw "health status is '$state'" }
    "status=$state"
}
Invoke-Check '/api/executions/current' {
    $execution = Get-Json '/api/executions/current'
    if ([string]$execution.state -ne 'RUNNING') { throw "state is '$($execution.state)'" }
    'state=RUNNING'
}
Invoke-Check '/api/workers' {
    $workers = @(Get-Json '/api/workers')
    $alive = @($workers | Where-Object { $_.alive -eq $true })
    $partitions = @($alive | ForEach-Object { $_.partitions } | ForEach-Object { $_ }) | Select-Object -Unique
    if ($workers.Count -ne 3 -or $alive.Count -ne 3 -or $partitions.Count -ne 6) {
        throw "workers=$($workers.Count), alive=$($alive.Count), covered partitions=$($partitions.Count) (expected 3, 3, 6)"
    }
    '3 workers alive; 6 partitions covered'
}
Invoke-Check '/api/simulator/state' {
    $simulator = Get-Json '/api/simulator/state'
    $trucks = @($simulator.trucks)
    if ($trucks.Count -ne 6) {
        return @{ Warn = $true; Reason = "trucks=$($trucks.Count) (expected 6)" }
    }
    "trucks=$($trucks.Count)"
}
Invoke-Check '/api/recovery/history' {
    $null = Get-Json '/api/recovery/history'
    'new UI backend answered'
}
Invoke-Check '/api/glorious/pipelines/fields' {
    $null = Get-Json '/api/glorious/pipelines/fields'
    'answered'
}
Invoke-Check '/api/glorious/ai/health' {
    try {
        $health = Get-Json '/api/glorious/ai/health'
        if ($health.ok -ne $true) { throw 'AI bridge reports ok=false' }
    } catch {
        throw "$($_.Exception.Message); start with powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1"
    }
    'AI bridge answered and reports ok=true'
} -Warn

if ($script:failed) {
    Write-Output 'VERDICT FAIL'
    exit 1
}
Write-Output 'VERDICT PASS (warnings may need attention)'
exit 0
