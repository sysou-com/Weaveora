# Weaveora: put this Windows box into S3 sleep OR S4 hibernate, then auto-wake after N hours.
#   ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI/GBK, so non-ASCII text
#   corrupts parameter binding (measured 2026-09-21: a Chinese -Description made
#   Register-ScheduledTask fail on RunLevel while looking like it had succeeded).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File sleep_n_hours.ps1 -Hours 12 -Mode Hibernate
#   powershell -NoProfile -ExecutionPolicy Bypass -File sleep_n_hours.ps1 -Hours 6  -Mode Suspend
#
# Why Hibernate is the better bet on THIS box (measured 2026-09-21):
#   every S3 suspend so far resumed within 1-12 s (Kernel-Power 42 -> 107), wake source
#   reported as "ACPI Wake Alarm"; S4 writes RAM to hiberfil.sys so a bogus wake cannot
#   lose the session (and usually does not reproduce).
#
# Tasks created/re-armed (no deletions):
#   Weaveora-Sleep-Now : suspend/hibernate at now + DelayMin
#   Weaveora-Wake      : wake at now + Hours (WakeToRun => RTC wake timer)
# Cancel:
#   schtasks /Delete /TN Weaveora-Sleep-Now /F
#   schtasks /Delete /TN Weaveora-Wake /F
param(
    [double]$Hours = 6,
    [double]$DelayMin = 3,
    [ValidateSet('Suspend', 'Hibernate')][string]$Mode = 'Suspend'
)
$ErrorActionPreference = 'Stop'

$sleepName = 'Weaveora-Sleep-Now'
$wakeName  = 'Weaveora-Wake'
$now       = Get-Date
$sleepAt   = $now.AddMinutes($DelayMin)
$wakeAt    = $now.AddHours($Hours)

# Task 1: delayed power action. 'Suspend' = S3, 'Hibernate' = S4.
# disableWakeEvent = $false so the wake timer below can still wake the machine.
$psAction = "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::$Mode,`$false,`$false)"
$a1 = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ("-NoProfile -WindowStyle Hidden -Command `"" + $psAction + "`"")
$t1 = New-ScheduledTaskTrigger -Once -At $sleepAt
$s1 = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
Register-ScheduledTask -TaskName $sleepName -Action $a1 -Trigger $t1 -Settings $s1 -Force `
  -Description "Weaveora: $Mode this PC (cancel: schtasks /Delete /TN Weaveora-Sleep-Now /F)" | Out-Null
Enable-ScheduledTask -TaskName $sleepName | Out-Null

# Task 2: auto-wake after N hours (RTC wake timer).
$a2 = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument '/c exit'
$t2 = New-ScheduledTaskTrigger -Once -At $wakeAt
$s2 = New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
      -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
Register-ScheduledTask -TaskName $wakeName -Action $a2 -Trigger $t2 -Settings $s2 -Force `
  -Description 'Weaveora: wake this PC (cancel: schtasks /Delete /TN Weaveora-Wake /F)' | Out-Null

'--- verify ---'
"now    = " + $now.ToString('yyyy-MM-dd HH:mm:ss')
"mode   = $Mode"
"action = " + $sleepAt.ToString('yyyy-MM-dd HH:mm:ss')
"wake   = " + $wakeAt.ToString('yyyy-MM-dd HH:mm:ss')
foreach ($n in @($sleepName, $wakeName)) {
    $t = Get-ScheduledTask -TaskName $n
    $i = Get-ScheduledTaskInfo -TaskName $n
    "TASK {0} state={1} wake={2} next={3}" -f $n, $t.State, $t.Settings.WakeToRun, $i.NextRunTime
}
