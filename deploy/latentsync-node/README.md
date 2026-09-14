# deploy/latentsync-node —— LatentSync 节点补丁包（Weaveora 定制）

> **单一事实源**：worker 跑在 API 服务器，LatentSync 节点跑在 GPU 服务器。`files/` 里是从
> 开发机 ComfyUI 节点目录同步出来的**已打过 Weaveora 补丁**的 5 个文件，GPU 机上的副本靠
> `apply.sh` 覆盖。**任何节点侧改动（本目录 files/）都必须：① bump 版本号 ② 重新 apply 到 GPU 机
> ③ 在 `docs/lipsync-setup.md` 第九节登记。**

## 为什么要有这个包

worker（API 服务器）与 ComfyUI 节点（GPU 服务器）之间只能手工同步代码，曾真实踩过：

| 事故 | 根因 | 后果 |
|---|---|---|
| 第1镜两段台词都驱动同一张脸 | GPU 机上是**旧 inference.py**，不认「内联 JSON 规格」，把 JSON 当文件路径 → 读不到 → 退回「取最大脸」 | 别人的嘴贴到宝玉脸上，画面被毁 |
| 段落内帧错位 / 画面撕裂 | 旧代码按方案时间窗切段（与配音实际时长不等）→ LatentSync `loop_video` 正放+倒放凑帧 | 时间轴错乱 |
| 出画/转身后锁错人 | 静态点选逐帧找最近脸 | 换人、画面被毁 |

补丁内容（版本 `2026-09-14.1`）：

| 文件 | 补了什么 |
|---|---|
| `latentsync/utils/face_detector.py` | **轨迹锁人**（点选/定妆照只播种，之后按「上一帧框 + 自累积人脸特征」逐帧跟住，绝不换人）；**质量闸门**（跟丢/脸太小<64px/侧脸>0.55 → 该帧不驱动）；`last_driven` 契约 |
| `latentsync/utils/image_processor.py` | **贴回遮罩收紧到嘴+下巴**（原版覆盖整个下半脸+两侧脸颊，是"糊脸/糊到邻脸"的直接原因；模型输入仍用原遮罩，避免分布偏移）；`build_paste_mask()` 支持 `WEAVEORA_PASTE_BAND/GROW/BLUR` 调参 |
| `latentsync/pipelines/lipsync_pipeline.py` | 逐帧「驱动/不驱动」贯通（不驱动 = 输出原帧）；贴回用新遮罩；`WEAVEORA_DEBUG_BOX` / 请求级 `debugBox` 调试画框；中间片按源片 fps 写 |
| `nodes.py` | `WEAVEORA_NODE_VERSION` / `WEAVEORA_NODE_FEATURES` + `GET /weaveora/version` 接口；节点日志打印收到的锁定规格（内联 JSON / 文件路径） |
| `scripts/inference.py` | 锁定规格支持**内联 JSON**（跨机不用传文件）；解析 `debugBox` |

## 用法（在 GPU 服务器上）

```bash
# 1) 取补丁包（二选一）
curl -fsSLO https://sysou.com/weaveora-node/latentsync-node-patch.tar.gz
tar xzf latentsync-node-patch.tar.gz            # 解出 latentsync-node/

# 2) 打补丁（默认路径 /home/dataset-local/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper）
bash latentsync-node/apply.sh
# 或显式指定： bash latentsync-node/apply.sh /path/to/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper

# 3) 重启 ComfyUI（节点代码只在启动时加载）
#    按你的启动方式，例如 systemctl restart comfyui / bash deploy/weaveora_boot.sh

# 4) 自检（本地 + 公网）
curl -s http://127.0.0.1:8001/weaveora/version
bash latentsync-node/verify.sh http://127.0.0.1:8001
```

## worker 侧会强校验

`worker/comfy_client.py` 在跑对口型前会调 `GET {comfy}/weaveora/version`，要求以下能力齐全，
缺任何一个都会**直接失败并打印修复指引**（不再静默降级成「最大脸」）：

```
point_lock  inline_spec  track_lock  quality_gate  paste_mask  fps_pin
```

应急跳过（仅在确知风险时）：worker 环境变量 `WEAVEORA_SKIP_NODE_CHECK=1`。

## 维护

- 开发机上改完节点代码 → `bash deploy/latentsync-node/sync_from_local.sh`（把改动同步进 `files/`）
- bump `nodes.py` 里的 `WEAVEORA_NODE_VERSION`（例如 `2026-09-14.2`）
- 重新打包上传：`bash deploy/latentsync-node/pack.sh`（生成 tar.gz 并 scp 到 API 服务器 webroot）
- 在 `docs/lipsync-setup.md` 第九节登记一行（版本 / 日期 / 改了什么）
