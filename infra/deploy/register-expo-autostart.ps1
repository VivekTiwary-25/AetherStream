#requires -Version 5.1
[CmdletBinding()]
param([switch]$Unregister)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskName = 'AetherStream-Expo-Autostart'
$taskPath = '\'

if ($Unregister) {
    $existing = Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        Unregister-ScheduledTask -TaskName $taskName -TaskPath $taskPath -Confirm:$false
        Write-Output "Unregistered $taskName. Running containers and log files are unchanged."
    } else {
        Write-Output "$taskName is not registered."
    }
    return
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scriptPath = Join-Path $PSScriptRoot 'expo-autostart.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw "Missing $scriptPath" }
$account = New-Object System.Security.Principal.NTAccount("$env:COMPUTERNAME\Arena")
$userSid = $account.Translate([System.Security.Principal.SecurityIdentifier]).Value
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $userSid
$trigger.Delay = 'PT30S'
# Interactive, limited token: Docker Desktop and the result console belong to Arena's session.
$principal = New-ScheduledTaskPrincipal -UserId $userSid -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $scriptPath
$action = New-ScheduledTaskAction -Execute "$PSHOME/powershell.exe" -Argument $arguments -WorkingDirectory $repoRoot
$task = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Start the existing aether demo at Arena logon, wait for health, then show DEMO READY.'
Register-ScheduledTask -TaskName $taskName -TaskPath $taskPath -InputObject $task -Force | Out-Null
Write-Output "Registered $taskName for $($account.Value), 30 seconds after logon, including on battery."
Write-Output "Checkout: $repoRoot. Registration does not start the task; this checkout must remain available."
