#!/usr/bin/env python3
"""range_download.py —— Weaveora 大模型/大文件下载器（多分片 + 断点续传 + 进度日志）。

规范（用户既定偏好，勿回退单线程）：
  * 下载前先 HEAD 核验：Content-Length（总大小）、Accept-Ranges: bytes（不支持则明确提示，
    绝不明知不支持还分片）；可再传 --expect-size/--expect-sha256 做最终校验（“先查版本/字节对不对”）。
  * 默认 10 进程（分片）并行 Range 下载，支持断点续传：中断后重跑同一命令继续，不重复下已完成的片。
  * 进度日志（--log，默认 <out>.dl.log）每 ~8s 一行：时间/总大小/已完成/速度；结束写 DONE 或 FAILED。
  * 网络错误指数退避重试几次；持续失败则保留 .part 分片并退出（可重跑续传），不删半成品。
  * 单线程仅在服务端不支持 Range 时回退（日志注明）。

用法示例（后台，看日志即可）：
  setsid nohup python3 range_download.py <URL> /data/models/xx.safetensors \
      --parts 10 --log /data/weaveora/dl.log --expect-size 10000000000 >/dev/null 2>&1 &
  tail -f /data/weaveora/dl.log
"""
import argparse
import hashlib
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

UA = "Mozilla/5.0 (range_download/1.0) weaveora"


def now_str():
    return datetime.now(timezone.utc).astimezone().strftime("%H:%M:%S")


def log(msg, path=None):
    line = "[%s] %s" % (now_str(), msg)
    print(line, flush=True)
    if path:
        try:
            with open(path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            pass


def http_headers(url, timeout=30):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return dict(r.headers), r.status
    except urllib.error.HTTPError as e:
        return dict(e.headers), e.code
    except Exception as e:
        return {"error": str(e)}, 0


def total_size_from_headers(h):
    cl = h.get("Content-Length")
    return int(cl) if cl and cl.isdigit() else None


def fetch_range(url, start, end, outpath, exp_end, retries=5, timeout=60):
    """下载 [start, end] 一个分片（end/exp_end 均为含端点）；断点续传：outpath 已有 n 字节则从 start+n 续下。"""
    if end < start:
        return True
    need = exp_end - start + 1   # 本片应下载字节数
    have = os.path.getsize(outpath) if os.path.exists(outpath) else 0
    if have > need:
        have = need  # 防御：片超长按完成处理
    if have >= need:
        return True
    mode = "r+b" if have > 0 else "wb"
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": UA,
                "Range": "bytes=%d-%d" % (start + have, end),
            })
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status = r.status
                with open(outpath, mode) as f:
                    if status == 206 and have > 0:
                        f.seek(have)
                    while True:
                        chunk = r.read(1024 * 1024)
                        if not chunk:
                            break
                        f.write(chunk)
                fsize = os.path.getsize(outpath)
                if fsize < need:
                    have = fsize
                    raise IOError("短读: 期望 %d 实得 %d" % (need, fsize))
                return True
        except Exception as e:
            have = os.path.getsize(outpath) if os.path.exists(outpath) else 0
            if attempt >= retries:
                return False
            backoff = min(2 ** attempt * 3, 60)
            time.sleep(backoff)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("out")
    ap.add_argument("--parts", type=int, default=10)
    ap.add_argument("--log", default=None)
    ap.add_argument("--expect-size", type=int, default=None)
    ap.add_argument("--expect-sha256", default=None)
    ap.add_argument("--retries", type=int, default=5)
    args = ap.parse_args()

    logpath = args.log or (args.out + ".dl.log")
    log("start url=%s parts=%d -> %s" % (args.url, args.parts, args.out), logpath)

    h, status = http_headers(args.url)
    if h.get("error"):
        log("FAILED head error: %s" % h["error"], logpath)
        sys.exit(1)
    total = total_size_from_headers(h)
    if total is None:
        log("FAILED 无法获取 Content-Length（服务器可能不支持，另找源）", logpath)
        sys.exit(1)
    supports_range = str(h.get("Accept-Ranges", "")).lower() == "bytes"
    if args.expect_size and args.expect_size != total:
        log("FAILED 大小不符: 期望 %d 实际 %d —— 源不对/版本不对，勿下载" % (args.expect_size, total), logpath)
        sys.exit(1)
    log("verify ok total=%d (%.2f GiB) ranges=%s expect_size=%s" % (
        total, total / 1073741824.0, supports_range, args.expect_size or "-"), logpath)

    parts_dir = args.out + ".parts"
    os.makedirs(parts_dir, exist_ok=True)
    if not supports_range:
        # 服务端不支持 Range → 明确回退单线程（唯一允许的单线程场景）
        log("warn 服务端不支持 Range，回退单线程整文件下载", logpath)
        part = os.path.join(parts_dir, "0")
        ok = fetch_range(args.url, 0, total - 1, part, total - 1, args.retries)
        if not ok:
            log("FAILED 下载中断，可重跑续传（.parts 保留）", logpath)
            sys.exit(2)
        pieces = [part]
    else:
        chunk = total // args.parts
        ranges = []
        start = 0
        for i in range(args.parts):
            end = total - 1 if i == args.parts - 1 else start + chunk - 1
            if end < start:
                end = start
            ranges.append((i, start, end))
            start = end + 1
        results = {}
        errors = []

        def worker(i, s, e):
            p = os.path.join(parts_dir, "%05d" % i)
            ok = fetch_range(args.url, s, e, p, e, args.retries)
            results[i] = ok
            if not ok:
                errors.append(i)

        threads = [threading.Thread(target=worker, args=r, daemon=True) for r in ranges]
        done_before = sum(
            os.path.getsize(os.path.join(parts_dir, "%05d" % i))
            for i in range(args.parts) if os.path.exists(os.path.join(parts_dir, "%05d" % i))
        )
        log("resume base done=%d (%.2f%%), launching %d threads" % (
            done_before, 100.0 * done_before / total if total else 0, len(threads)), logpath)

        # 进度线程（~8s 一行）
        stop = threading.Event()

        def progress():
            prev = done_before
            prev_t = time.time()
            while not stop.is_set():
                time.sleep(8)
                cur = sum(
                    os.path.getsize(os.path.join(parts_dir, "%05d" % i))
                    for i in range(args.parts) if os.path.exists(os.path.join(parts_dir, "%05d" % i))
                )
                t2 = time.time()
                dt = t2 - prev_t
                spd = int((cur - prev) / dt) if dt > 0 else 0
                prev, prev_t = cur, t2
                pct = 100.0 * cur / total if total else 0
                log("progress done=%d (%.2f%%) total=%d speed=%d B/s" % (cur, pct, total, spd), logpath)

        pt = threading.Thread(target=progress, daemon=True)
        pt.start()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        stop.set()
        pt.join(timeout=1)

        if errors:
            cur = sum(os.path.getsize(os.path.join(parts_dir, "%05d" % i))
                      for i in range(args.parts) if os.path.exists(os.path.join(parts_dir, "%05d" % i)))
            log("FAILED 分片失败 idx=%s done=%d/%d —— 可重跑续传" % (errors, cur, total), logpath)
            sys.exit(2)
        pieces = [os.path.join(parts_dir, "%05d" % i) for i in range(args.parts)]

    # 合并 + 校验
    tmp = args.out + ".tmp"
    with open(tmp, "wb") as fout:
        for p in pieces:
            with open(p, "rb") as f:
                while True:
                    b = f.read(4 * 1024 * 1024)
                    if not b:
                        break
                    fout.write(b)
    fsize = os.path.getsize(tmp)
    if fsize != total:
        log("FAILED 合并后大小不符 %d != %d，删除临时文件，重跑" % (fsize, total), logpath)
        os.remove(tmp)
        sys.exit(2)
    sha = None
    if args.expect_sha256:
        hh = hashlib.sha256()
        with open(tmp, "rb") as f:
            while True:
                b = f.read(4 * 1024 * 1024)
                if not b:
                    break
                hh.update(b)
        sha = hh.hexdigest()
        if sha != args.expect_sha256:
            log("FAILED sha256 不符: 期望 %s 实得 %s" % (args.expect_sha256, sha), logpath)
            os.remove(tmp)
            sys.exit(2)
    os.replace(tmp, args.out)
    import shutil
    shutil.rmtree(parts_dir, ignore_errors=True)
    log("DONE out=%s size=%d sha256=%s" % (args.out, fsize, sha or "-"), logpath)


if __name__ == "__main__":
    main()
