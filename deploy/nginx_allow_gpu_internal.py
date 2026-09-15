#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把新 GPU 服务器的出口 IP 加入生产机 nginx 的 /weaveora/internal/ 白名单。

背景：worker 通过 /internal/* 通道 claim 任务，该通道仅对白名单 IP 开放
      （location ^~ /weaveora/internal/ { allow ...; deny all; }）。
      新 GPU 服务器出口 IP（实测 2026-09-13：36.103.182.60，入口为 <GPU公网地址>）
      不在名单里 -> 403。

用法（在生产机执行，需 root）：
    sudo python3 nginx_allow_gpu_internal.py                  # 默认加 36.103.182.60
    sudo python3 nginx_allow_gpu_internal.py 1.2.3.4 5.6.7.8  # 加指定 IP/CIDR
    sudo python3 nginx_allow_gpu_internal.py --cidr 36.103.182.0/24   # 整段放行（出口 IP 可能漂移时用）

幂等：已存在则跳过。改完自动 nginx -t + reload；失败自动回滚。
"""
import io
import os
import shutil
import subprocess
import sys
import time

CONF_CANDIDATES = ["/etc/nginx/nginx.conf"]
ANCHOR = "location ^~ /weaveora/internal/ {"
DEFAULT_IPS = ["36.103.182.60"]


def find_conf():
    for c in CONF_CANDIDATES:
        if os.path.isfile(c):
            return c
    print("找不到 nginx 配置")
    sys.exit(1)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cidr = None
    if "--cidr" in sys.argv:
        i = sys.argv.index("--cidr")
        cidr = sys.argv[i + 1]
    targets = [cidr] if cidr else (args or DEFAULT_IPS)

    conf = find_conf()
    s = io.open(conf, encoding="utf-8").read()

    if ANCHOR not in s:
        print("锚点未命中：%s" % ANCHOR)
        sys.exit(1)

    lines = s.split("\n")
    out, inserted, i = [], 0, 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        if line.strip() == ANCHOR.strip():
            # 收集该块内已有的 allow/deny
            j = i + 1
            block = []
            while j < len(lines) and lines[j].strip() not in ("}", ""):
                block.append(lines[j])
                j += 1
            existing = "\n".join(block)
            new_allows = [t for t in targets if ("allow %s;" % t) not in existing]
            if not new_allows:
                print("  已存在，跳过：%s" % ", ".join(targets))
            else:
                for t in new_allows:
                    out.append("            allow %s;" % t)
                    inserted += 1
                    print("  + allow %s;" % t)
            i += 1
            continue
        i += 1

    if not inserted:
        s2 = "\n".join(out)
        if s2 == s:
            print("无改动")
            return
    new_s = "\n".join(out)

    backup = conf + ".bak-allowgpu-" + time.strftime("%Y%m%d%H%M%S")
    shutil.copy2(conf, backup)
    io.open(conf, "w", encoding="utf-8").write(new_s)
    print("已写入 %s（备份 %s）" % (conf, backup))

    t = subprocess.run(["nginx", "-t"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True)
    print(t.stdout.strip())
    if t.returncode != 0:
        shutil.copy2(backup, conf)
        print("nginx -t 失败，已回滚")
        sys.exit(1)

    r = subprocess.run(["systemctl", "reload", "nginx"], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, universal_newlines=True)
    print(r.stdout.strip() or "reload ok")
    print("完成。可用下面命令验证（在 GPU 服务器执行）：")
    print("  curl -s -o /dev/null -w '%%{http_code}\\n' https://sysou.com/weaveora/internal/nodes/register")


if __name__ == "__main__":
    main()
