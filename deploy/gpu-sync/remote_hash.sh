#!/usr/bin/env bash
# ============================================================================
# Weaveora · GPU 镜像同步 —— 远端哈希器（三种模式，按需触发，绝不无脑全量）
#
# 用法：本地把"路径清单"从 stdin 灌进来（ssh 的 stdin 给脚本用，注意这里用
#       ssh 'bash -s -- <mode> <root>' 时 stdin 会被脚本 read 消耗，调用方式见
#       gpu_mirror_sync.js 的 runRemoteHash()）。
#
#   bash remote_hash.sh quick  /opt/weaveora   < 路径列表(每行一个, 相对 ROOT)
#       输出: <quick_sha256>\t<相对路径>
#       quick 定义（本地 Node 侧必须逐字节等价实现）：
#           sha256( 十进制size + "\n" + 前1MiB + （size>1MiB 时）后1MiB )
#
#   bash remote_hash.sh full   /opt/weaveora   < 路径列表
#       输出: <全文 sha256>\t<相对路径>          ← 大文件全量校验（慢，磁盘密集）
#
#   bash remote_hash.sh prefix /opt/weaveora   < 行格式 "<相对路径>\t<字节数>"
#       输出: <前N字节 sha256>\t<相对路径>
#       ← 用于"断点续传前先证明本地半截文件是远端文件的真前缀"
#
#   bash remote_hash.sh small  /opt/weaveora   < 路径列表
#       输出: <全文 sha256>\t<相对路径>
#       ← 小文件批量通道：xargs 一把梭 sha256sum，避免每文件起 2 个进程
#
# 注意：大文件请套 ionice/nice 跑（调用方负责），别把生产 GPU 盒的 IO 打满。
# ============================================================================
set -u

MODE="${1:-quick}"
ROOT="${2:-/opt/weaveora}"
Q=$((1024 * 1024))          # 1 MiB 采样块
BATCH=200                   # small 模式每批文件数

cd "$ROOT" || { echo "#ERR cannot cd $ROOT" >&2; exit 1; }

case "$MODE" in
  # ---------------------------------------------------------------- 小文件批量
  small)
    tr '\n' '\0' | xargs -0 -r -n "$BATCH" sha256sum -- 2>/dev/null \
      | sed 's/^\([0-9a-f]\{64\}\)  \(\.\/\)\?/\1\t/' 
    ;;
  # ---------------------------------------------------------------- 逐文件模式
  quick|full|prefix)
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      if [ "$MODE" = prefix ]; then
        p="${line%%$'\t'*}"
        n="${line##*$'\t'}"
        [ "$p" = "$n" ] && continue
      else
        p="$line"
      fi
      [ -e "$p" ] || { printf 'ERR_MISSING\t%s\n' "$p"; continue; }

      if [ "$MODE" = full ]; then
        sha256sum -- "$p" 2>/dev/null | sed 's/^\([0-9a-f]\{64\}\)  /\1\t/'
        continue
      fi

      if [ "$MODE" = prefix ]; then
        # 前 n 字节：直接 count
        h=$(dd if="$p" bs=1048576 count="$n" iflag=count_bytes status=none 2>/dev/null \
            | sha256sum | cut -d' ' -f1)
        printf '%s\t%s\n' "$h" "$p"
        continue
      fi

      # quick：头 1MiB + （size>1MiB 时）尾 1MiB。
      # ★ 坑：iflag=count_bytes 下 count 的单位是【字节】——写 count=1 只会采 1 个字节（踩过）
      # ★ 坑：-- 软链要用 stat -L（否则拿到的是链长度 85 字节，不是目标大小）
      s=$(stat -Lc %s -- "$p" 2>/dev/null) || { printf 'ERR_STAT\t%s\n' "$p"; continue; }
      h=$(
        {
          printf '%s\n' "$s"
          dd if="$p" bs="$Q" count="$Q" iflag=count_bytes status=none 2>/dev/null
          if [ "$s" -gt "$Q" ]; then
            dd if="$p" bs="$Q" skip=$((s - Q)) iflag=skip_bytes status=none 2>/dev/null
          fi
        } | sha256sum | cut -d' ' -f1
      )
      printf '%s\t%s\n' "$h" "$p"
    done
    ;;
  *)
    echo "#ERR unknown mode $MODE" >&2
    exit 2
    ;;
esac
