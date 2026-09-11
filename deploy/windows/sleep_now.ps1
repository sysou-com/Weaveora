# Sleep now and auto-wake after N hours.
# NOTE: keep this file PURE ASCII. PowerShell 5.1 reads BOM-less UTF-8 as GBK,
#       and garbled non-ASCII can break parsing (bitten twice already).
#   usage: powershell -File sleep_now.ps1 -Hours 1 -DelaySeconds 60
param(
  [double]$Hours = 1,
  [int]$DelaySeconds = 60
)
$ErrorActionPreference = "Continue"
$sid = "S-1-5-21-76743959-3632852079-1463324553-500"   # Administrator
$log = "D:\ComfyUI\_setup\wake.log"
$name = "WeaveWakeAuto"
$wakeAt = (Get-Date).AddHours($Hours)

Write-Host ("now        : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ("wake at    : {0}" -f $wakeAt.ToString('yyyy-MM-dd HH:mm:ss'))
Write-Host ("sleep in   : {0}s" -f $DelaySeconds)

# 1) allow wake timers (AC + DC)
powercfg.exe /SETACVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 | Out-Null
powercfg.exe /SETDCVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 | Out-Null
powercfg.exe /SETACTIVE SCHEME_CURRENT | Out-Null

# 2) one-shot wake task (WakeToRun). No nested quotes in -Argument:
#    nested quotes make New-ScheduledTaskAction throw, and
#    $ErrorActionPreference='Continue' would swallow it -> assert afterwards.
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument ("/c echo %date% %time% woken-by-WeaveWakeAuto>> {0}" -f $log)
$trigger = New-ScheduledTaskTrigger -Once -At $wakeAt
$settings = New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Highest
Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null

$verify = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
if ($null -eq $verify) {
  Write-Host "!!! wake task registration FAILED - aborting sleep" -ForegroundColor Red
  exit 1
}
$info = Get-ScheduledTaskInfo -TaskName $name
Write-Host ("wake task  : {0} (State={1}, NextRun={2})" -f $name, $verify.State, $info.NextRunTime)

# 3) detached sleeper: does not block the caller
$inner = "Start-Sleep -Seconds $DelaySeconds; rundll32.exe powrprof.dll,SetSuspendState 0,1,0"
Start-Process powershell.exe -ArgumentList "-NoProfile", "-WindowStyle", "Hidden", "-Command", $inner -WindowStyle Hidden
Write-Host ("scheduled  : sleep in {0}s (S3)" -f $DelaySeconds)
