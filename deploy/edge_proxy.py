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
    app.router.add_route("*", "/{tail:.*}", handle)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    log.info("edge proxy 监听 %s:%d", args.host, args.port)
    web.run_app(app, host=args.host, port=args.port, print=None, access_log=None)


if __name__ == "__main__":
    main()
