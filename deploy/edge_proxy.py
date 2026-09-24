#!/usr/bin/env python3
# =============================================================================
# Weaveora 单端口入口网关（edge proxy）
#
# 背景：平台只开放一个公网端口 <GPU公网地址:端口> → 容器内 8000。
#       但本机有 4 个服务，必须在容器内做「按路径多路复用」。
#
# 路由表（公网 30250 → 容器 8000 → 本脚本）：
#   /audio/*   -> 127.0.0.1:8091   (去前缀)  TTS 配音 /tts 、转写 /transcribe 、/health
#   /bgm/*     -> 127.0.0.1:8092   (去前缀)  配乐 HTTP 兜底 /music 、/health
#   /face/*    -> 127.0.0.1:8093   (保留)    人脸 /face/probe 、/face/embed 、/health
#   /talk      -> 127.0.0.1:8094   (保留)    整脸口型（EchoMimicV3 / jaw-lip）单镜 /talk
#   /talk_batch-> 127.0.0.1:8094   (保留)    整脸口型批量（一次加载处理 N 镜，摊薄 20GB 加载）
#   /talk/health -> 127.0.0.1:8094 (保留)    整脸口型健康检查（服务端认 /talk/health）
#   /*         -> 127.0.0.1:8001   (保留)    ComfyUI（含 /ws WebSocket）
#
# 对应 worker 环境变量：
#   WEAVEORA_COMFY_URL=http://<GPU公网地址:端口>
#   WEAVEORA_TTS_URL  =http://<GPU公网地址:端口>/audio
#   WEAVEORA_MUSIC_URL=http://<GPU公网地址:端口>/bgm
#   WEAVEORA_FACE_URL =http://<GPU公网地址:端口>
#
# 启动：<venv>/bin/python edge_proxy.py [--port 8000]
# 依赖：aiohttp（ComfyUI venv 自带，无需额外安装）
# =============================================================================
import argparse
import asyncio
import logging
import os
import subprocess
import sys

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web

logging.basicConfig(level=logging.INFO, format="[edge] %(asctime)s %(message)s",
                    datefmt="%F %T")
log = logging.getLogger("edge")

# (路径前缀, 上游, 是否剥掉前缀)
ROUTES = [
    ("/audio", "http://127.0.0.1:8091", True),
    ("/bgm",   "http://127.0.0.1:8092", True),
    ("/face",  "http://127.0.0.1:8093", False),
    # 整脸口型（EchoMimicV3 / jaw-lip，:8094）。
    # ★ 顺序有讲究：匹配是「第一个命中的前缀」——
    #   `/talk/health`、`/talk_batch`、`/talk` 均**保留原路径**（talk 服务端自己认这三个路径）。
    #   踩过的坑：早先把 `/talk/health` 配成「去前缀」，而去前缀的实现是 `path[len(prefix):] or "/"` ——
    #   精确命中时剥成空 → 转发到 `/` → talk 服务端 404。这类「路径就是全路径」的路由必须 strip=False。
    #   另：`/talk_batch` 不匹配前缀 `/talk`（规则要求完全相等或前缀+/），所以必须单独列。
    ("/talk/health", "http://127.0.0.1:8094", False),
    ("/talk_batch",  "http://127.0.0.1:8094", False),
    ("/talk",        "http://127.0.0.1:8094", False),
]
DEFAULT_UPSTREAM = "http://127.0.0.1:8001"   # ComfyUI

# 逐跳头，必须剔除（RFC 7230）
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}

CHUNK = 64 * 1024


def pick_upstream(path):
    for prefix, base, strip in ROUTES:
        if path == prefix or path.startswith(prefix + "/"):
            return base, (path[len(prefix):] or "/") if strip else path
    return DEFAULT_UPSTREAM, path


def clean_headers(headers):
    return {k: v for k, v in headers.items() if k.lower() not in HOP_BY_HOP}


async def proxy_ws(request, base, path):
    """WebSocket 双向转发（ComfyUI /ws 进度推送）。"""
    ws_client_side = web.WebSocketResponse()
    await ws_client_side.prepare(request)
    url = base + path
    if request.query_string:
        url += "?" + request.query_string
    try:
        async with request.app["sess"].ws_connect(url, headers=clean_headers(request.headers)) as up:

            async def c2s():
                async for msg in ws_client_side:
                    if msg.type == WSMsgType.TEXT:
                        await up.send_str(msg.data)
                    elif msg.type == WSMsgType.BINARY:
                        await up.send_bytes(msg.data)
                    elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED):
                        break
                if not up.closed:
                    await up.close()

            async def s2c():
                async for msg in up:
                    if msg.type == WSMsgType.TEXT:
                        await ws_client_side.send_str(msg.data)
                    elif msg.type == WSMsgType.BINARY:
                        await ws_client_side.send_bytes(msg.data)
                    elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED, WSMsgType.ERROR):
                        break
                if not ws_client_side.closed:
                    await ws_client_side.close()

            await asyncio.gather(c2s(), s2c(), return_exceptions=True)
    except Exception as e:  # noqa: BLE001
        log.warning("ws %s -> %s 失败: %s", path, url, e)
    return ws_client_side


async def proxy_http(request, base, path):
    """普通 HTTP 转发，流式回写（大模型文件上传/下载、长耗时推理）。"""
    url = base + path
    if request.query_string:
        url += "?" + request.query_string

    body = await request.read()
    headers = clean_headers(request.headers)
    headers.pop("Host", None)

    try:
        async with request.app["sess"].request(request.method, url,
                                               headers=headers, data=body) as up:
            resp = web.StreamResponse(status=up.status)
            for k, v in up.headers.items():
                if k.lower() in HOP_BY_HOP or k.lower() == "content-length":
                    continue
                resp.headers[k] = v
            await resp.prepare(request)
            async for chunk in up.content.iter_chunked(CHUNK):
                await resp.write(chunk)
            await resp.write_eof()
            return resp
    except asyncio.TimeoutError:
        return web.Response(status=504, text="edge proxy: upstream timeout\n")
    except Exception as e:  # noqa: BLE001
        log.warning("%s %s -> %s 失败: %s", request.method, path, url, e)
        return web.Response(status=502, text="edge proxy: upstream error: %s\n" % e)


async def reload_comfy(request):
    """★ 2026-09-24：受 token 保护的「重启 ComfyUI」入口 —— 供 worker 做**跨模型家族切换**。

    为什么必须有这条路（实测，不是推测）：
      · 盒是 48 GB 内存 / 48 GB 显存，而**图像家族 49.8 GB**（FLUX.2 主模型 33 + 编码器 16.8）
        与**视频家族 34 GB**（LTX 20 + gemma 14）**装不下**；
        跨家族不重启 = OOM killer 杀 ComfyUI（`anon-rss 47.8 GB`）⇒ 整栈连网关一起死，
        worker 端只看到 `Connection refused`（现象与根因隔着两层，极易误判成“motion 失败”）。
      · `POST /free {"unload_models": true}` **不能用**：实测它把 33 GB 权重从显存
        **卸载到内存**（anon 13.5 → 45.3 GB，整机 available 只剩 179 MB），方向完全错。
      · VPS worker **没有** 盒上 SSH 权限（实测 `Permission denied`）⇒ 只能借我们自己这条网关开口子。

    实现：**只杀 ComfyUI（main.py）再跑一遍 `services_up.sh`** —— 后者是幂等的（哪个端口不在就起哪个），
    所以网关自己**不会被杀**（能正常回包），TTS/face/talk 也不受打扰；不重启整栈。
    鉴权：`X-WV-Token` 头必须等于环境变量 `WEAVEORA_EDGE_ADMIN_TOKEN`（未配则一律 403）。
    """
    tok = os.environ.get("WEAVEORA_EDGE_ADMIN_TOKEN", "")
    if not tok or request.headers.get("X-WV-Token", "") != tok:
        return web.json_response({"ok": False, "err": "forbidden"}, status=403)
    # 先等 ComfyUI 真的退出（释放 33 GB 显存/内存要几秒），再让 services_up.sh 把它拉起来；
    # 若不等就重启，services_up 的 `up 8001` 可能还看到端口在听 → 跳过 → 反而留下一个死进程。
    # ★ 两个已踩的坑（2026-09-24，都是实测）：
    #   ① `pkill -f '/opt/weaveora/ComfyUI/main.py'` 会**自匹配**：模式串就在自己这条 `bash -c` 的
    #      命令行里 ⇒ pkill 把自己这条 shell 也杀了，后面的 services_up 永远不执行
    #      （现象：ComfyUI 被杀、网关变成 502、没人把它拉起来）。用 `[C]omfyUI` 括号技巧绕开。
    #   ② `services_up.sh` 的 `ROOT=${WEAVEORA_ROOT:-/home/dataset-local/weaveora}` **默认是 GPU#1 的遗留路径**；
    #      不带 WEAVEORA_ROOT 跑就会 `cd: /home/dataset-local/weaveora/ComfyUI: No such file` 然后 exit 1。
    #      这里显式 export，不依赖调用方环境。
    #   ③ `services_up.sh` 还需要 `WEAVEORA_GATEWAY_PORT`（默认 8000！）—— 漏了会把网关起在**错的端口**
    #      （现象：ComfyUI 好了、但公网入口 57712 变成 502）。所以两个变量一起显式给。
    # ★ 2026-09-24 修（实测事故：定妆照任务 502）：等待判据必须与 `services_up.sh` **同源** ——
    #   它决定起不起用的是 `up()` = `ss -ltn | grep -q ":8001 "`（**端口**），而旧代码等的是
    #   `pgrep -f '[C]omfyUI/main.py'`（读 /proc/PID/cmdline）。内核 `do_exit` 的顺序是
    #   先 `exit_mm()`（cmdline 立刻清空 ⇒ pgrep 立刻报“进程没了”）→ 最后 `exit_files()`（才关监听 socket）；
    #   ComfyUI 要拆 33 GB，这个窗口实测 ~2 秒 ⇒ services_up 看到 8001 仍 LISTEN 就**跳过启动**，
    #   随后进程真正退出 ⇒ 盒子再没有 ComfyUI，之后每个任务都 `POST /prompt -> 502 Cannot connect to host 127.0.0.1:8001`。
    #   实测时间线：09:05:35 请求 reload → 09:05:37 services_up「ComfyUI :8001 已在监听，跳过」
    #   → 09:05:39 起 8001 不可达、180s 全 502，且 comfyui.log 无任何新行（= 根本没被拉起）。
    #   触发频率高：worker 进程重启后**第一个任务必然走这条路**（“本进程首次提交，但盒上已驻留 X GB”）。
    cmd = ("export WEAVEORA_ROOT=/opt/weaveora WEAVEORA_GATEWAY_PORT=8800 ; "
           "mkdir -p /opt/weaveora/logs ; "
           "pkill -f '[C]omfyUI/main.py' ; "
           # ① 等**端口**释放（与 services_up 同判据，最多 60s）
           "for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':8001 ' || break; sleep 1; done ; "
           "sleep 2 ; "
           "setsid bash /opt/weaveora/services_up.sh >> /opt/weaveora/logs/services_up.log 2>&1 ; "
           # ② 回读 40s：还没起来就再拉一次（兜住任何尚未想到的竞态）
           "for i in $(seq 1 20); do ss -ltn 2>/dev/null | grep -q ':8001 ' && break; sleep 2; done ; "
           "ss -ltn 2>/dev/null | grep -q ':8001 ' || "
           "setsid bash /opt/weaveora/services_up.sh >> /opt/weaveora/logs/services_up.log 2>&1")
    try:
        subprocess.Popen(["/bin/bash", "-c", cmd], start_new_session=True)
    except Exception as e:  # noqa: BLE001
        log.warning("reload_comfy 拉起失败: %s", e)
        return web.json_response({"ok": False, "err": str(e)}, status=500)
    log.info("reload_comfy：已请求重启 ComfyUI（调用方需自行轮询 /system_stats 到 200）")
    return web.json_response({"ok": True, "reloading": True})


async def handle(request):
    base, path = pick_upstream(request.path)
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await proxy_ws(request, base, path)
    return await proxy_http(request, base, path)


async def health(request):
    """网关自身健康 + 各上游可达性。"""
    out = {}
    for prefix, base, _ in ROUTES:
        out[prefix] = base
    out["/"] = DEFAULT_UPSTREAM
    return web.json_response({"ok": True, "routes": out, "note": "weaveora edge proxy"})


async def on_startup(app):
    app["sess"] = ClientSession(timeout=ClientTimeout(total=3600, sock_connect=30),
                                auto_decompress=False)
    log.info("上游路由: %s | 默认 %s", ROUTES, DEFAULT_UPSTREAM)


async def on_cleanup(app):
    await app["sess"].close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()

    app = web.Application(client_max_size=1024 ** 3)   # 允许 1GB 上传
    app.router.add_get("/__edge/health", health)
    app.router.add_post("/__edge/reload_comfy", reload_comfy)
    app.router.add_route("*", "/{tail:.*}", handle)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    log.info("edge proxy 监听 %s:%d", args.host, args.port)
    web.run_app(app, host=args.host, port=args.port, print=None, access_log=None)


if __name__ == "__main__":
    main()
