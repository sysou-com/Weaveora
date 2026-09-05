# weaveora-worker（§19/§11.2）

GPU/引擎 worker（Python，纯标准库依赖）。同一份代码两种执行端：
- `WEAVEORA_WORKER_MODE=stub`（默认）：占位 PNG，无 GPU 可跑通全链路（当前生产 VPS 用）
- `WEAVEORA_WORKER_MODE=comfy`：真 ComfyUI 出图
  - `WEAVEORA_COMFY_URL`（默认 http://127.0.0.1:8188）
  - txt2img；任务带参考图时走 **IP-Adapter（一致性锚定，§30 #23）**，
    节点缺失/失败默认降级纯 txt2img（`WEAVEORA_COMFY_FALLBACK_TXT2IMG=0` 关闭降级）

## 协议
出站连 API（反而不连中间件）：注册 `/internal/nodes/register` → 心跳线程 → 轮询 claim
`/internal/nodes/{id}/claim` → 阶段进度 `/internal/jobs/{id}/progress` →
参考图经 `/internal/assets?key=…` 拉取 → 引擎出图 → 产物传 `/internal/jobs/{id}/assets` →
`/internal/jobs/{id}/complete|fail`。鉴权头 `X-Worker-Token`（对应 API `weaveora.worker.token`）。

## 本地联调
```bash
# stub 常驻
python stub_worker.py
# comfy 模式（需 ComfyUI 在跑；或先跑假引擎测试）
python test_comfy_flow.py        # 起假 ComfyUI + 全场景（含参考图锚定），需本地 api
python e2e_local.py              # 全链路（注册→项目→导演→确认→任务→stub 完成）
```

## 环境变量
`WEAVEORA_API_BASE`（默认 http://localhost:8080）、`WEAVEORA_WORKER_TOKEN`（默认 dev-worker-token）、
`WEAVEORA_WORKER_NAME`、`WEAVEORA_WORKER_WORKSPACE`（BYO 填工作区 uuid；空=节点池）。
