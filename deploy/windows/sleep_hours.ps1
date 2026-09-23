# 安排「N 小时后唤醒」并让机器进入 S3 睡眠（Weaveora GPU 机夜间休眠用）
#   用法：powershell -File sleep_hours.ps1 5
param([double]$Hours = 5)

$ErrorActionPreference = "Continue"
$sid = "S-1-5-21-76743959-3632852079-1463324553-500"   # Administrator
$log = "D:\ComfyUI\_setup\wake.log"
$wakeAt = (Get-Date).AddHours($Hours)
$name = "WeaveWakeAuto"

Write-Host ("现在     : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ("计划唤醒 : {0}" -f $wakeAt.ToString('yyyy-MM-dd HH:mm:ss'))

# 1) 允许唤醒计时器（交流/电池都开）
powercfg.exe /SETACVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 | Out-Null
powercfg.exe /SETDCVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 | Out-Null
powercfg.exe /SETACTIVE SCHEME_CURRENT | Out-Null

# 2) 一次性唤醒任务（WakeToRun）
#    注意：-Argument 里嵌套转义引号会让 New-ScheduledTaskAction 抛错；
#    而 $ErrorActionPreference='Continue' 会把它吞掉，于是“任务没注册但机器已睡”——
#    所以这里用不含引号的简单命令串，并显式断言注册结果。
$action = New-ScheduledTaskAction -Execute "cmd.exe" `
  -Argument ("/c echo %date% %time% woken-by-WeaveWakeAuto>> {0}" -f $log)
$trigger = New-ScheduledTaskTrigger -Once -At $wakeAt
$settings = New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Highest
Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger `
  -Settings $settings -Principal $principal -Force | Out-Null

$verify = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
if (-not $verify) {
  Write-Host "!!! 唤醒任务注册失败，取消睡眠" -ForegroundColor Red
  exit 1
}
Write-Host "已注册唤醒任务: $name (State=$($verify.State))"

# 3) 校验唤醒计时器已登记
Write-Host "--- 活动唤醒计时器 ---"
powercfg.exe /waketimers

# 4) 进入睡眠（休眠未启用 => 走 S3）
Write-Host "--- 进入睡眠 ---"
Start-Sleep -Seconds 2
rundll32.exe powrprof.dll,SetSuspendState 0,1,0
