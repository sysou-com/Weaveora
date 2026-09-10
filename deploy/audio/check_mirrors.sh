#!/usr/bin/env bash
# 测各 PyPI 镜像对大 wheel 的实际速度（取 nvidia-cudnn-cu12 8.9.2.26，731MB）
set -uo pipefail
PKG_PAT='cudnn_cu12-8\.9\.2\.26-py3-none-manylinux1_x86_64\.whl'

test_mirror() {
  local base="$1"
  local idx="$base/nvidia-cudnn-cu12/"
  local rel
  rel=$(curl -s --max-time 25 "$idx" | grep -oE "href=\"[^\"]*${PKG_PAT}[^\"]*\"" | head -1 | sed 's/href="//;s/"//;s/#.*//')
  if [ -z "$rel" ]; then echo "$base -> 索引无链接"; return; fi
  # 解析相对路径：相对 $idx（去掉尾部包名目录）
  local url
  url=$(python3 - "$idx" "$rel" <<'PY'
import sys, urllib.parse
base, rel = sys.argv[1], sys.argv[2]
print(urllib.parse.urljoin(base, rel))
PY
)
  printf '%-46s ' "$base"
  curl -sL -r 0-40000000 -o /dev/null --max-time 25 \
    -w 'code=%{http_code} speed=%{speed_download} B/s got=%{size_download}\n' "$url"
}

test_mirror "https://pypi.tuna.tsinghua.edu.cn/simple"
test_mirror "https://mirrors.aliyun.com/pypi/simple"
test_mirror "https://mirrors.cloud.tencent.com/pypi/simple"
test_mirror "https://mirror.nju.edu.cn/pypi/web/simple"
test_mirror "https://pypi.org/simple"
