#requires -Version 5.1
[CmdletBinding()]
param([switch]$Unregister, [switch]$DryRun)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskName = 'AetherStream-Tunnel-Watchdog'
$taskPath = '\'

if ($DryRun) {
    if ($Unregister) {
        Write-Output "DRY RUN: Stop and unregister only $taskName if registered; leave the tunnel task and public-link files unchanged."
    } else {
        Write-Output "DRY RUN: Register $taskName at Arena logon with an interactive, limited token, including on battery."
        Write-Output "DRY RUN: Run powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot/tunnel-watchdog.ps1`"."
        Write-Output 'DRY RUN: Unlimited execution time; ignore duplicate instances. Registration does not start the task.'
    }
    return
}

if ($Unregister) {
    $existing = Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        Stop-ScheduledTask -TaskName $taskName -TaskPath $taskPath
        Unregister-ScheduledTask -TaskName $taskName -TaskPath $taskPath -Confirm:$false
        Write-Output "Stopped and unregistered $taskName. Tunnel task and public-link files are unchanged."
    } else {
        Write-Output "$taskName is not registered."
    }
    return
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scriptPath = Join-Path $PSScriptRoot 'tunnel-watchdog.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw "Missing $scriptPath" }
$account = New-Object System.Security.Principal.NTAccount("$env:COMPUTERNAME\Arena")
$userSid = $account.Translate([System.Security.Principal.SecurityIdentifier]).Value
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $userSid
$principal = New-ScheduledTaskPrincipal -UserId $userSid -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $scriptPath
$action = New-ScheduledTaskAction -Execute "$PSHOME/powershell.exe" -Argument $arguments -WorkingDirectory $repoRoot
$task = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Watch the public quick-tunnel version endpoint and publish replacement URLs after tunnel recovery.'
Register-ScheduledTask -TaskName $taskName -TaskPath $taskPath -InputObject $task -Force | Out-Null
Write-Output "Registered $taskName for $($account.Value) at logon, including on battery."
Write-Output "Checkout: $repoRoot. Registration does not start the task; this checkout must remain available."
