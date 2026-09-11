# ComfyUI reverse SSH tunnel + API local-forward + TTS reverse forward (constant, self-healing)
# Remote forward:  175.12.60.225:127.0.0.1:18188 -> this host 127.0.0.1:8188   (ComfyUI)
# Local forward :  this host 127.0.0.1:18080  -> 175.12.60.225:127.0.0.1:8080 (weaveora API)
# P9 remote fwd :  175.12.60.225:127.0.0.1:18091 -> this host 127.0.0.1:8091   (TTS/whisper)
# 18188/18091 only visible on 175.12.60.225 loopback (single-host whitelist).
# Single-instance mutex; reconnect loop; log to tunnel.log
$ErrorActionPreference = "SilentlyContinue"

$ssh = "C:\Windows\System32\OpenSSH\ssh.exe"
if (-not (Test-Path $ssh)) { $ssh = "C:\Program Files\Git\usr\bin\ssh.exe" }
$key = "C:\Users\Administrator\.ssh\comfy_tunnel_ed25519"
$server = "root@175.12.60.225"
$remote = "127.0.0.1:18188:127.0.0.1:8188"   # -R comfy
$local  = "127.0.0.1:18080:127.0.0.1:8080"   # -L api
# P9：再暴露一个反向口给 tts 服务（API 侧调 /transcribe 用 whisper），同样只绑服务器 loopback
$remoteTts = "127.0.0.1:18091:127.0.0.1:8091"  # -R tts
$log = Join-Path $PSScriptRoot "tunnel.log"
$mutexName = "Global\ComfyReverseTunnel"

$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(0)) { exit }   # another instance already running
function Log($m) { Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Log "tunnel supervisor started (-R comfy 18188, -L api 18080)"
$up = $false
while ($true) {
    if (-not (Test-Path $key)) { Log "key missing: $key"; Start-Sleep -Seconds 30; continue }
    Log "connecting ssh -R $remote -R $remoteTts -L $local ($server) ..."
    $up = $true
    $t0 = Get-Date
    & $ssh -i $key -N -R $remote -R $remoteTts -L $local -p 22 `
        -o ServerAliveInterval=20 -o ServerAliveCountMax=3 `
        -o ExitOnForwardFailure=yes -o ConnectTimeout=10 `
        -o StrictHostKeyChecking=accept-new $server 2>> $log
    $rc = $LASTEXITCODE
    $aliveSec = [int]((Get-Date) - $t0).TotalSeconds
    Log "ssh exited rc=$rc after ${aliveSec}s; reconnect in 8s"

    # Self-heal: a SHORT-LIVED session (connected then exited immediately) almost always
    # means a stale sshd session on the server still holds the reverse-forward ports
    # 18188/18091, so the new connection fails to bind them; combined with
    # ExitOnForwardFailure=yes that becomes an endless rc=255 retry loop
    # (observed after sleep/resume). Kill the stale holders and retry.
    # We do not hold those ports at that moment, so this is safe.
    # Server side now also sets ClientAliveInterval=30 x3 to reap phantoms at the source.
    if ($aliveSec -lt 20) {
        Log "short-lived session -> killing stale forward holders on $server (18188/18091)"
        & $ssh -i $key -o BatchMode=yes -o ConnectTimeout=10 $server `
            "fuser -k -n tcp 18188 18091 >/dev/null 2>&1; sleep 1; true" 2>> $log
    }
    Start-Sleep -Seconds 8
}
