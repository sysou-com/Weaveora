# Weaveora: sleep this PC now-ish and auto-wake after 6 hours  (ASCII only on purpose:
# Windows PowerShell 5.1 reads .ps1 as ANSI/GBK, so non-ASCII text corrupts parameters.)
#
#   Weaveora-Sleep-Now : suspends the machine 3 minutes from now (cancellable)
#   Weaveora-Wake-6h   : wakes it 6 hours from now via WakeToRun (RTC wake timer)
#
# Prereqs already verified on this box: powercfg -a has "Standby (S3)";
# RTCWAKE (allow wake timers) is enabled on both AC and DC.
$ErrorActionPreference = 'Stop'

$sleepName = 'Weaveora-Sleep-Now'
$wakeName  = 'Weaveora-Wake-6h'
$now       = Get-Date
$sleepAt   = $now.AddMinutes(3)
$wakeAt    = $now.AddHours(6)

# Task 1: delayed suspend. Use .NET SetSuspendState('Suspend') to force S3 sleep
# (rundll32 SetSuspendState may hibernate when hibernation is enabled).
$psSleep = "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend',`$false,`$false)"
$a1 = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ("-NoProfile -WindowStyle Hidden -Command `"" + $psSleep + "`"")
$t1 = New-ScheduledTaskTrigger -Once -At $sleepAt
$s1 = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
Register-ScheduledTask -TaskName $sleepName -Action $a1 -Trigger $t1 -Settings $s1 -Force `
  -Description 'Weaveora: suspend this PC in 3 min (cancel: schtasks /Delete /TN Weaveora-Sleep-Now /F)' | Out-Null

# Task 2: auto-wake after 6 hours (WakeToRun registers an RTC wake timer).
$a2 = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument '/c exit'
$t2 = New-ScheduledTaskTrigger -Once -At $wakeAt
$s2 = New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
      -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
Register-ScheduledTask -TaskName $wakeName -Action $a2 -Trigger $t2 -Settings $s2 -Force `
  -Description 'Weaveora: wake this PC after 6h (cancel: schtasks /Delete /TN Weaveora-Wake-6h /F)' | Out-Null

'--- verify ---'
"now = " + $now.ToString('yyyy-MM-dd HH:mm:ss')
foreach ($n in @($sleepName, $wakeName)) {
    $t = Get-ScheduledTask -TaskName $n
    $i = Get-ScheduledTaskInfo -TaskName $n
    "TASK {0} state={1} wake={2} next={3}" -f $n, $t.State, $t.Settings.WakeToRun, $i.NextRunTime
}
