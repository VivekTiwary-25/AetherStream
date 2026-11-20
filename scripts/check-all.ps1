[CmdletBinding()]
param(
    [switch]$WithDocker,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDir = Join-Path $root '.check-logs'
$rows = New-Object System.Collections.Generic.List[object]
$failed = $false

$nodeBin = Join-Path $env:USERPROFILE 'tools/node'
if ((Test-Path $nodeBin) -and (($env:PATH -split ';') -notcontains $nodeBin)) {
    $env:PATH = "$nodeBin;$env:PATH"
}

function Get-JavaVersion([string]$JavaPath) {
    $output = & $env:ComSpec /d /s /c ('"' + $JavaPath + '" -version 2>&1')
    return ($output -join "`n")
}
function Add-Skip([string]$Name) {
    [void]$rows.Add([pscustomobject]@{ Suite = $Name; Result = 'SKIP'; Seconds = 0; Log = '-' })
}
function Invoke-Check([string]$Name, [string]$Directory, [int]$TimeoutSeconds, [string]$Command) {
    if ($DryRun) {
        Write-Output ("{0}: (timeout {1}s) {2} [cwd: {3}]" -f $Name, $TimeoutSeconds, $Command, $Directory)
        return
    }
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
    $safeName = $Name -replace '[^a-zA-Z0-9_-]', '_'
    $logPath = Join-Path $logDir ($safeName + '.log')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/s', '/c', $Command) -WorkingDirectory $Directory -RedirectStandardOutput $logPath -RedirectStandardError ($logPath + '.err') -PassThru
    $null = $process.Handle # Retain the handle so Windows PowerShell 5.1 can read ExitCode after waiting.
    $completed = $process.WaitForExit($TimeoutSeconds * 1000)
    if (-not $completed) {
        try { $process.Kill() } catch { }
        $process.WaitForExit()
        $status = 124
        Add-Content -Path $logPath -Value "Timed out after $TimeoutSeconds seconds."
    } else {
        $status = $process.ExitCode
    }
    if (Test-Path ($logPath + '.err')) {
        Get-Content ($logPath + '.err') | Add-Content $logPath
        Remove-Item ($logPath + '.err')
    }
    $timer.Stop()
    $result = if ($status -eq 0) { 'PASS' } elseif ($status -eq 124) { 'TIMEOUT' } else { 'FAIL' }
    if ($status -ne 0) { $script:failed = $true }
    [void]$rows.Add([pscustomobject]@{ Suite = $Name; Result = $result; Seconds = [math]::Round($timer.Elapsed.TotalSeconds, 1); Log = ('.check-logs/' + $safeName + '.log') })
    Write-Output ("{0}: {1} (exit {2})" -f $Name, $result, $status)
}

$web = Join-Path $root 'web'
Invoke-Check 'web-npm-ci' $web 300 'if not exist node_modules npm ci'
Invoke-Check 'web-lint' $web 300 'npm run lint'
Invoke-Check 'web-test' $web 600 'npm test -- --maxWorkers=2'
Invoke-Check 'web-build' $web 600 'npm run build'
Invoke-Check 'web-no-mock' $web 120 'npm run check:no-mock'

$javaHome21 = $env:JAVA_HOME_21
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
$currentVersion = if ($javaCommand) { Get-JavaVersion $javaCommand.Source } else { '' }
if (-not $DryRun -and $currentVersion -notmatch 'version "21(?:\.|"|\+)') {
    $candidates = @($javaHome21, $env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Java')) | Where-Object { $_ }
    $jdk21 = $null
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'bin/java.exe')) {
            if ((Get-JavaVersion (Join-Path $candidate 'bin/java.exe')) -match 'version "21(?:\.|"|\+)') { $jdk21 = $candidate; break }
        } elseif (Test-Path $candidate) {
            $found = Get-ChildItem -Path $candidate -Directory -Filter '*21*' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found -and (Test-Path (Join-Path $found.FullName 'bin/java.exe')) -and ((Get-JavaVersion (Join-Path $found.FullName 'bin/java.exe')) -match 'version "21(?:\.|"|\+)')) { $jdk21 = $found.FullName; break }
        }
    }
    if (-not $jdk21) {
        Write-Host 'Java 21 is required; current java version was not 21 and no JDK 21 was found (JAVA_HOME_21/common paths).' -ForegroundColor Red
        $script:failed = $true
        Add-Skip 'java-test'
    } else {
        $env:JAVA_HOME = $jdk21
        $env:PATH = (Join-Path $jdk21 'bin') + ';' + $env:PATH
    }
}
if ($DryRun -or ($currentVersion -match 'version "21(?:\.|"|\+)') -or ($jdk21 -and $env:JAVA_HOME -eq $jdk21)) {
    Invoke-Check 'java-test' $root 900 '.\gradlew.bat test --no-daemon'
}
if ($WithDocker -and ($DryRun -or ($currentVersion -match 'version "21(?:\.|"|\+)') -or ($jdk21 -and $env:JAVA_HOME -eq $jdk21))) { Invoke-Check 'java-integration' $root 1200 '.\gradlew.bat :tests:integrationTest --no-daemon --console=plain' } else { Add-Skip 'java-integration' }
Invoke-Check 'ai-bridge-unittest' $root 180 'python -I -m unittest discover -s scripts/ai-bridge -p "test_*.py" -v'
if ($env:GLORIOUS_URL -and $env:GLORIOUS_URL -notlike '*:8088*') { Invoke-Check 'playwright-glorious' (Join-Path $root 'tests/ui') 2100 'npm run test:glorious' } else { Add-Skip 'playwright-glorious' }
if ($env:CLASSIC_URL) { Invoke-Check 'playwright-classic' (Join-Path $root 'tests/ui') 2100 'npm test -- --config=playwright.config.ts' } else { Add-Skip 'playwright-classic' }

if ($DryRun) { exit 0 }
Write-Output ''
$rows | Format-Table @{Label='suite'; Expression={$_.Suite}}, @{Label='result'; Expression={$_.Result}}, @{Label='seconds'; Expression={$_.Seconds}}, @{Label='log file'; Expression={$_.Log}} -AutoSize
if ($failed) { Write-Output 'Overall: FAIL'; exit 1 }
Write-Output 'Overall: PASS'
