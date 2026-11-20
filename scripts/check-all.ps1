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
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaPath
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            [void]$process.WaitForExit(1000)
            return ''
        }
        if ($process.ExitCode -ne 0) { return '' }
        return ($process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd())
    } catch {
        return ''
    } finally {
        $process.Dispose()
    }
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

$javaSearches = @(
    @{ Name = 'JAVA_HOME'; Path = $env:JAVA_HOME; Filter = $null }
    @{ Name = 'JAVA_HOME_21_X64'; Path = $env:JAVA_HOME_21_X64; Filter = $null }
    @{ Name = 'JDK21_HOME'; Path = $env:JDK21_HOME; Filter = $null }
    @{ Path = (Join-Path $env:ProgramFiles 'Eclipse Adoptium'); Filter = 'jdk-21*' }
    @{ Path = (Join-Path $env:ProgramFiles 'Java'); Filter = 'jdk-21*' }
    @{ Path = (Join-Path $env:ProgramFiles 'Microsoft'); Filter = 'jdk-21*' }
    @{ Path = (Join-Path $env:USERPROFILE '.jdks'); Filter = '*21*' }
    @{ Path = (Join-Path $env:USERPROFILE '.gradle\jdks'); Filter = '*21*' }
)
$javaPlacesSearched = New-Object System.Collections.Generic.List[string]
$jdk21 = $null
foreach ($search in $javaSearches) {
    if ($search.Filter) {
        [void]$javaPlacesSearched.Add((Join-Path $search.Path $search.Filter))
        $candidates = @(Get-ChildItem -LiteralPath $search.Path -Directory -Filter $search.Filter -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -ExpandProperty FullName)
    } else {
        $value = if ($search.Path) { $search.Path } else { '<unset>' }
        [void]$javaPlacesSearched.Add(('{0}={1}' -f $search.Name, $value))
        $candidates = @($search.Path)
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $javaPath = Join-Path $candidate 'bin\java.exe'
        $javacPath = Join-Path $candidate 'bin\javac.exe'
        if ((Test-Path -LiteralPath $javaPath -PathType Leaf) -and
            (Test-Path -LiteralPath $javacPath -PathType Leaf) -and
            ((Get-JavaVersion $javaPath) -match 'version "21(?:\.|"|\+)')) {
            $jdk21 = (Get-Item -LiteralPath $candidate).FullName
            break
        }
    }
    if ($jdk21) { break }
}
if ($jdk21) {
    $env:JAVA_HOME = $jdk21
    $env:PATH = (Join-Path $jdk21 'bin') + ';' + $env:PATH
    # Quote the entire -D argument for cmd.exe/gradlew.bat, including homes with spaces.
    $gradleJavaHome = '"-Dorg.gradle.java.home=' + $jdk21 + '"'
    Write-Output ("Java 21 home: {0}" -f $jdk21)
    Invoke-Check 'java-test' $root 900 ('.\gradlew.bat ' + $gradleJavaHome + ' test --no-daemon')
    if ($WithDocker) {
        Invoke-Check 'java-integration' $root 1200 ('.\gradlew.bat ' + $gradleJavaHome + ' :tests:integrationTest --no-daemon --console=plain')
    } else {
        Add-Skip 'java-integration'
    }
} else {
    Write-Output ("Java checks: SKIP (JDK 21 not found). Places searched:`n  " + ($javaPlacesSearched -join "`n  "))
    Add-Skip 'java-test'
    Add-Skip 'java-integration'
    if (-not $DryRun) { $script:failed = $true }
}
Invoke-Check 'ai-bridge-unittest' $root 180 'python -I -m unittest discover -s scripts/ai-bridge -p "test_*.py" -v'
if ($env:GLORIOUS_URL -and $env:GLORIOUS_URL -notlike '*:8088*') { Invoke-Check 'playwright-glorious' (Join-Path $root 'tests/ui') 2100 'npm run test:glorious' } else { Add-Skip 'playwright-glorious' }
if ($env:CLASSIC_URL) { Invoke-Check 'playwright-classic' (Join-Path $root 'tests/ui') 2100 'npm test -- --config=playwright.config.ts' } else { Add-Skip 'playwright-classic' }

if ($DryRun) { exit 0 }
Write-Output ''
$rows | Format-Table @{Label='suite'; Expression={$_.Suite}}, @{Label='result'; Expression={$_.Result}}, @{Label='seconds'; Expression={$_.Seconds}}, @{Label='log file'; Expression={$_.Log}} -AutoSize
if ($failed) { Write-Output 'Overall: FAIL'; exit 1 }
Write-Output 'Overall: PASS'
