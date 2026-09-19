#!/usr/bin/env bash
# ============================================================================
# Weaveora · GPU 镜像同步 —— 远端清单探测器（只 stat，不读文件内容）
#
# 用法（本地调用，脚本内容经 ssh stdin 送过去，无需在盒子上落文件）：
#   ssh gpu 'bash -s -- /opt/weaveora' < remote_probe.sh
#
# 输出 TSV（stdout，逐行流式）：
#   <type>\t<size>\t<mtime_epoch>\t<relpath>\t<link_target>
#     type = f(真文件) | l(软链) | d(目录)
#     relpath 以 ./ 开头（相对 ROOT）
#   首行 #HOST / 末行 #END 作为边界标记（便于失败判定）
#
# 铁律：
#   * -xdev 不跨文件系统 → /addDisk 上的软链只列"链"，不跟着爬（否则清单爆掉）
#   * 只做 stat，绝不 hash / 绝不 tar（hash 交给 remote_hash.sh，按需触发）
#   * 易变目录（日志 / ComfyUI 输出 / 临时文件）默认剪掉，避免"边同步边变"
# ============================================================================
set -u

ROOT="${1:-/opt/weaveora}"

cd "$ROOT" || { echo "#ERR cannot cd $ROOT" >&2; exit 1; }

printf '#HOST %s %s ROOT=%s\n' "$(hostname)" "$(date -Is)" "$ROOT"

find . -xdev \
  \( \
    -path './logs' \
    -o -path './ComfyUI/output'  -o -path './ComfyUI/temp' \
    -o -path './ComfyUI/user'    -o -path './talk_work' \
    -o -path './talk_in'         -o -path './img_out' \
    -o -name '__pycache__'       -o -name '*.pyc' \
  \) -prune -o \
  \( -type f -o -type l -o -type d \) \
  -printf '%y\t%s\t%T@\t%p\t%l\n'

# ---- 软链解析段（关键）：软链的 %s 是"链长度"不是目标大小，
#      不解引用就分不清 /addDisk 那 4 个 20G 模型是截断还是完整
#      输出: L\t<file|dir|broken>\t<目标字节数>\t<relpath>
find . -xdev -type l -print0 | while IFS= read -r -d '' p; do
  if [ -e "$p" ]; then
    if [ -d "$p" ]; then k=dir; else k=file; fi
    s=$(stat -L -c %s -- "$p" 2>/dev/null || echo -1)
  else
    k=broken; s=-1
  fi
  printf 'L\t%s\t%s\t%s\n' "$k" "$s" "$p"
done

printf '#END\n'
