#!/usr/bin/env bash
# 预下载 torch 2.3.1 的大轮子（nvidia-* / triton），绕开 pip 在大文件上卡死的问题。
# 镜像优先级：南大(实测 36.9MB/s) > 清华(36.5MB/s)；curl 断点续传 + 4 路并行。
# 产物：/data/audio/wheels/*.whl
set -uo pipefail

WHEELS=/data/audio/wheels
mkdir -p "$WHEELS"
# 注意：bash 数组无法通过 export 传给 xargs 起的子 shell，这里用普通字符串。
MIRRORS="https://mirror.nju.edu.cn/pypi/web/simple https://pypi.tuna.tsinghua.edu.cn/simple"

# "包名 版本"（版本留空=取索引里最后一个匹配）
PKGS="
nvidia-cuda-nvrtc-cu12 12.1.105
nvidia-cuda-runtime-cu12 12.1.105
nvidia-cuda-cupti-cu12 12.1.105
nvidia-cudnn-cu12 8.9.2.26
nvidia-cublas-cu12 12.1.3.1
nvidia-cufft-cu12 11.0.2.54
nvidia-curand-cu12 10.3.2.106
nvidia-cusolver-cu12 11.4.5.107
nvidia-cusparse-cu12 12.1.0.106
nvidia-nccl-cu12 2.20.5
nvidia-nvtx-cu12 12.1.105
triton 2.3.1
"

resolve_url() {  # $1=pkg  $2=ver
  local pkg="$1" ver="$2" m rel url
  for m in $MIRRORS; do
    rel=$(curl -s --max-time 30 "$m/$pkg/" | grep -oE 'href="[^"]+\.whl[^"]*"' \
          | sed 's/href="//;s/"//;s/#.*//' \
          | grep -E "/(${pkg//-/_}|${pkg})-${ver}-" | grep -E 'cp310|py3-none' | grep -E 'manylinux|linux_x86_64' | tail -1)
    [ -n "$rel" ] || continue
    url=$(python3 -c "import sys,urllib.parse;print(urllib.parse.urljoin('$m/$pkg/', '''$rel'''))")
    echo "$url"; return 0
  done
  return 1
}

get_one() {  # $1=pkg $2=ver
  local pkg="$1" ver="$2" url fn
  url=$(resolve_url "$pkg" "$ver") || { echo "  RESOLVE_FAIL $pkg==$ver"; return 1; }
  fn=$(basename "$url")
  if [ -f "$WHEELS/$fn" ]; then echo "  SKIP $fn"; return 0; fi
  echo "  START $fn"
  curl -sL -C - -o "$WHEELS/$fn" --retry 8 --retry-delay 3 --retry-all-errors \
       --connect-timeout 20 --max-time 2400 "$url"
  local got; got=$(stat -c %s "$WHEELS/$fn" 2>/dev/null || echo 0)
  echo "  DONE  $fn  ($((got/1048576)) MB)"
}
export -f resolve_url get_one
export WHEELS MIRRORS

echo "[$(date +%H:%M:%S)] 预下载大轮子 -> $WHEELS"
printf '%s\n' $PKGS | grep -v '^$' | xargs -P 4 -n 2 bash -c 'get_one "$0" "$1"'

echo
echo "[$(date +%H:%M:%S)] 汇总"
ls -la "$WHEELS" | tail -n +2 | awk '{printf "%12.1f MB  %s\n", $5/1048576, $9}'
du -sh "$WHEELS"
echo DONE
