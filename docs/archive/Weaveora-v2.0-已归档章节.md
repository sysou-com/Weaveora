# Weaveora.md 已归档章节（v2.0 瘦身，2026-09-19）

> **来源**：从仓库根 `Weaveora.md` 原文**逐字移出**（未做任何改写）。
> **性质**：历史存档 / 与代码重复的操作细则 / 已被重写章节的原文。
> ⚠️ **禁止**据此推断现行设计；现行设计以 `Weaveora.md` 正文 + §30 + `CLAUDE.md` 红线为准。
> **恢复路径**：① 本文件即原文备份 ② `git show <瘦身前的 commit>:Weaveora.md` ③ `.scratch/Weaveora.md.bak.20260919-233035`。
> 归档依据与逐项确认：2026-09-19 会话（用户同意 A/B/C 三类）。

---

## 〔归档 A1〕原 §33 版本沿革（历史快照，不代表当前设计）

## 33. 版本沿革（历史快照，不代表当前设计）

> 本文档在同一天内连续迭代 v1.2 → v2.0。以下为各历史版本的变更快照，**仅作决策过程存档；其中的模型/引擎基准（如 Wan2.2、SDXL 默认、SVD）已在后续版本淘汰，禁止当作现行设计引用**。当前有效内容以 v2.0 正文与 §30 为准。

- **v1.3（2026-09-04）**：① 出图通道 = 自建 ComfyUI + 后期 GPU 机（MirrorTalk 现有通道不变）；② MVP 队列 = Redis Streams（不引 RabbitMQ）；③ 不上 Nacos/Gateway/Sentinel；④ Java 21 + Boot 3.4.5 + Modulith 1.3.x；⑤ 存储 dev 本地 / prod OSS；⑥ 简化额度；⑦ 单人里程碑。
- **v1.4（2026-09-04）**：中间件（PG/Redis）统一部署 MirrorTalk VPS（#20）；PG 同实例分库 `weaveora`/`weaveora_test`；Redis db0 生产 / db1 测试；VPS 2C/8G/200G。
- **v1.5（2026-09-04）**：开发库 = 本机 PG `weaveora_dev`（上生产前过渡，#21）。
- **v1.6（2026-09-04）**：双轨执行（#22）：轨 1 云 GPU 先行 + 轨 2 BYO（RTX 3090/4070/4080/4090）；worker 出站连 API。
- **v1.7（2026-09-04）**：一致性范围（#23）：MVP = 产品/物体 + 虚构/IP 人物（非真实自然人）；真人 v1.0。**当时 motion 引擎候选为 Wan2.2，已废弃。**
- **v1.8（2026-09-04）**：定位收窄（#24/#25）：电影感降为长期定位、MVP 验收可量化；客群重切；MVP 收窄。**当时引用 Wan2.2-14B≈7.5s 单次上限，已按 Wan2.6/云 API 放宽。**
- **v1.9（2026-09-04）**：模型时效修正（正文不锁版本，一切走 Model Preset）；LLM → DeepSeek V4；出图质量基线 SDXL → FLUX 系；视频 Wan2.2 → 档位配置。
- **v2.0（2026-09-04，当前）**：模型矩阵中立化（#26）+ 卖点排序修订 + 云 API 后置 + 质量门禁降级；Sora 2 禁选。**另：新增 §0.1 高效使用本文档（读一次全量 → 按 PR 分模块 → 不回贴全文 → 模型版本看配置非历史）。**

---

*结束。实现时以本文 v2.0 为唯一产品 / 架构真源。第 30 章已锁定（含 v1.3 #3 #4 #13–#19、v1.4 #20、v1.5 #21、v1.6 #22、v1.7 #23、v1.8 #24 #25、v1.9 时效修订、v2.0 #26 及 #22-#25 修订），可以开工。*

---

## 〔归档 A3〕原 §32 附录

## 32. 附录

### 32.1 默认负面词（系统级，可被模板覆盖）

```
text, watermark, logo, subtitle, caption, signature, blurry, lowres,
deformed, extra limbs, badly drawn, jpeg artifacts, ugly, nsfw
```

用户 Brief 已明确需要字幕时，导演层可从 negative 去掉 `subtitle, caption, text`，但 watermark / logo 仍保留。

### 32.2 示例 Brief

1. 被水淹的巴洛克图书馆，月光从穹顶落下，不要人，电影静帧，16:9。  
2. 一只折纸船在暴雨城市的运河里漂过霓虹，12 秒，孤独，不要人脸。  
3. 青瓷盘子与一枝白梅，窗光，产品海报，1:1。

### 32.3 实现模型禁止事项（再强调）

- 不要用 Node / TanStack 充当生产后端。  
- 不要跳过确认闸门。  
- 不要在第一周接真实 GPU。  
- 不要把密钥写进前端 `.env` 的 `VITE_`。  
- 不要发明第二套状态枚举。  
- 不要 `ddl-auto=update`、不要 H2、不要 MyBatis、不要内存验证码。  
- 不要拷贝 MirrorTalk 的 `User` 实体进本仓库。  
- **不要选 Sora 2（2026 已关停）；不要把剪映导出当主卖点（v2.0 降为兼容项）；不要在 MVP 实现自动一致性特征向量门禁（伪精度，v2.0）。**

---

---

## 〔归档 A2〕原 §1.2 备选名

### 1.2 备选名（已不采用；若品牌层改名，架构不变）

| 名称 | 调性 | 不选原因 |
| --- | --- | --- |
| CineLoom 影织 | 更「电影工业」 | 拼写易被读成 cine-loom 工具名，品牌延展弱 |
| DirectorLoom 导影 | 强调导演 | 「导」字在中文互联网易与导航 / 导购撞车 |
| LuminaForge 光铸 | 更「引擎」 | Forge 在开发者圈过载 |
| SceneSmith 场景匠 | 更「手作」 | 国际名一般，中文名一般 |
| Frameweaver 织帧 | 直白 | 中文名略硬，英文过长 |


---

## 〔归档 C1〕原 §0.2 大文件下载铁律（全文）

### 0.2 大文件下载铁律（2026-09-13 新增，必须逐条遵守）

> **事故背景**：2026-09-13 安装 LatentSync（对口型）时，节点在 `import` 阶段调用 `huggingface_hub.snapshot_download` 拉 4.7 GiB 权重。该调用在 huggingface.co 直连不可达的环境下**建立了一条不返回的长连接**，既没有分片、也没有断点、还没有进度输出，把整轮会话堵死，只能强行终止。此后**任何大文件下载都按本节执行**。

**1）「大文件」定义**：单文件 ≥ 200 MiB（模型权重、数据集、镜像、安装包、音视频素材）。单文件 < 200 MiB 才允许普通 `curl -L -o`。

**2）唯一允许的下载方式**：10 路 Range 分片并发 + 断点续传，统一工具 **`deploy/windows/gpu_model_downloader.js`**。

```bash
# 单文件模式：<url> <输出绝对路径> [并发数，默认 10]
node deploy/windows/gpu_model_downloader.js "<URL>" "D:\path\to\file.bin" 10
```

它已具备：**10 个 curl 子进程**并发拉 Range 分片（真·10 进程，等价格 aria2 `-x10`）、`<file>.meta.json` 逐段 `pos` 断点（重跑自动续传）、`<file>.done` 完成标记、404 立即失败不空转、每 10s 一行进度日志、稀疏预分配。

> 为什么是 curl 子进程而不是 Node `https`：`hf-mirror.com` 的 TLS 握手会让 Node `https` 挂死（`huggingface_hub` 的长连接同理），而 curl 正常；且用户明确要求「10 进程分片」。

**3）禁止的下载方式**（本项目内视为违规）：

| 禁止 | 原因 |
|---|---|
| `huggingface_hub.snapshot_download` / `hf_hub_download` | 长连接、无分片、HF 直连不可达时挂死 |
| `modelscope.snapshot_download` | 同上（且节点自带的自动下载不可控、无进度） |
| `git lfs clone` / `git clone` 大仓库 | 全量长连接、不可断点续传、无进度 |
| 裸 `curl -o` / `wget` 下大文件 | 单连接、无断点（分片下载**必须**带 `Range`） |
| 前台同步等待、`Invoke-RestMethod` 阻塞拉流 | 把会话/终端堵死 |
| 有 `timeout` 的打包脚本里同步下载 | 超时被 kill 后留下半成品 |

**4）禁止前台阻塞（本事故的直接教训）**：下载**一律后台静默启动**，启动命令必须立即返回；agent 不得等待下载进程结束。

```powershell
# 正确：后台 + 隐藏窗口 + 输出落到日志（启动即返回）
Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',\
  'D:\workspace\Weaveora\deploy\windows\dl_latentsync.ps1' \
  -WindowStyle Hidden \
  -RedirectStandardOutput 'D:\model\_dl\<task>.log' \
  -RedirectStandardError  'D:\model\_dl\<task>.err.log'
```

**5）日志内容硬性要求**：每 10s 至少输出一行，必须同时含 **百分比 + 文件总大小 + 已下载大小 + 实时速度**（下载器已内置），并有明确的结束标记：

```
[17:12:03] [1/1] latentsync_unet.pt  42.1%  1.99 GiB/4.72 GiB  41.3 MiB/s
[17:15:40] [1/1] DONE latentsync_unet.pt  4.72 GiB  用时 3.7 min  均速 21.6 MiB/s
```

- 成功 = `DONE <file>` + `ALL_DONE`；失败 = `FAIL <file>: <原因>`。
- 日志固定放 `D:\model\_dl\<task>.log`（便于回看与排障），**下载完成后不要删日志**。

**6）「隔断时间提示去检查进度」——agent 义务**：下载在后台跑时，agent 必须**定期（每个后续回合至少一次，间隔 ≥ 5 分钟）**主动读一次日志并向用户播报一行摘要，格式：

```
下载进度：latentsync_unet.pt 42.1% / 4.72 GiB / 41.3 MiB/s（日志 D:\model\_dl\latentsync_dl.log）
```

不得只启动后就不管；也不得为了让用户看进度而改成前台阻塞跑。

**7）可续传判定标准**：只有 **`.meta.json`（记录每段 `pos`）+ 目标文件真实字节数** 算进度。

- ✅ 可续传：`<file>.meta.json` 存在，或目标文件大小 > 0 且 < 服务端总长 → 直接重跑同一命令，自动从断点继续。
- ❌ 不可续传：`huggingface_hub` 留下的 `<...>.incomplete` / `*.lock`、0 字节占位、`path_provider` 类临时缓存 —— **一律删除重下**，它们不记录分片偏移。

**8）镜像优先级（国内环境）**：

| 目标 | 通道 |
|---|---|
| HuggingFace 权重 | **`https://aifasthub.com/...`（2026-09-13 实测：Range 206、单连接 ~1.7 MiB/s、10 进程 ~40–80 MiB/s，回源 `us.aws.cdn.hf.co`）——首选** |
| HuggingFace 备用 | `https://gh-proxy.com/https://huggingface.co/...`（可达）；`https://hf-mirror.com`（**实测极不稳定：单连接 ~0.08 MiB/s，并发 TLS 握手会被掐掉，不要作为首选/唯一源**） |
| ModelScope 权重 | `resolve` 端点：`https://www.modelscope.cn/models/<org>/<repo>/resolve/master/<path>`（CDN 快、支持断点） |
| ModelScope ✅ 备用（**被限流时首选**） | `API` 端点：`https://modelscope.cn/api/v1/models/<org>/<repo>/repo?Revision=master&FilePath=<urlencode(path)>` |
| GitHub 源码/节点 zip | `https://ghfast.top/https://github.com/...` |
| PyPI 轮子 | 官方源（清华源作备选） |

> ⚠️ **ModelScope 的限流是按出口 IP / 路径而异的，不是全局**（2026-09-14 实测）：
> - GPU #1：`resolve` 端点正常（27–30 MiB/s）
> - GPU #2：`resolve` 端点**被限到 0.38 MB/s 且会中途挂死**；换 **API 端点**后 6.9 MB/s（单连）/
>   **28–53 MiB/s**（10 路）—— 约 **18 倍**
>
> 所以 **resolve 端点在某台机上慢/挂死时，不要以为 ModelScope 不行 —— 换 API 端点重测**；
> 同理 **换机器必须重测**（同一域名不同出口 IP 表现可能完全相反）。

> 镜像可用性随域名/时段变化。**换镜像前先用 `curl -r 0-0 -D -` 核对能返回 `206` + `content-range`，并跑一次 4 MiB 测速**（`-w '%{speed_download}'`），再决定用哪个；不要盲信历史结论。

`huggingface.co` 直连不可用，**禁止**在代码/脚本里写死 `huggingface.co` 作为权重来源（尤其禁止把 `hf_hub_download` / `snapshot_download` 写进节点初始化逻辑，见第 3/4 条）。

**9）完成即校验 —— 字节数相符 ≠ 内容正确**：下载器会自动比对 `Content-Range` 总长并写 `<file>.done`；
但**只看字节数是危险的**（2026-09-14 实测：umt5 文件**字节完全正确**但 `sha256` 不符 ——
`871298ad…` ≠ 官方 `c3355d30…`，是污染/不完整副本，下游会在加载时报奇怪的错）。

校验强度按下表分级（**大文件全量 sha256 在共享超卖的 CPU 上可能跑十几分钟且时间不可控**——
实测 GPU 容器 cgroup 14 核 / load ~20）：

| 文件尺寸 | 校验口径 | 能抓什么 |
|---|---|---|
| **≥ 1 GiB** | `quick`：字节数 + **safetensors 头结构**（JSON header 长度/张量数可解析） | 裁断、不完整、头损坏 |
| **< 1 GiB** | `full`：字节数 + **全量 sha256** | 上表全部 + 内容污染 |
| 关键小文件（config/json/LoRA） | `full` + 官方值硬编码在脚本里 | 版本错配 |

> 口径：**期望字节数 + 期望 sha256/结构特征都写进下载脚本**，校验不过就**删残片重下**，
> 不允许「字节对就放行」。

**10）下载与其它任务并行**：下载是后台进程，**不要「等它跑完再干别的」**；在下载进行时继续推进其它工作，按第 6 条周期性播报进度即可。

**11）禁止长 `sleep`（agent 与脚本同适用）**：

- **单次等待 ≤ 60s**。禁止 `sleep 300` / `sleep 600` / `Start-Sleep -Seconds 600` 这类死等。
- 需要等更久（如等模型加载、等任务收尾）→ **循环短等 + 每轮检查 + 每轮汇报一行**：

```bash
for i in $(seq 1 20); do        # 最多等 ~10 分钟，但每 30s 都看一次
  sleep 30
  <一次轻量状态查询>            # 端口/日志尾/进程/队列
  有结果 && break
  echo "  T+${i}x30s: <一行摘要>"
 done
```

- **为什么**：长 `sleep` ① 把会话/终端堵死；② **掩盖卡死**（等 10 分钟醒来才发现根本没开始跑）；
  ③ 无法中断、也没法根据中途变化改策略。
- **反面案例（本会话实测）**：`sleep 420` 后才发现下载已被限流卡住；另一处 `sleep 300` 后才发现任务早失败。
  → 正确做法是**短轮询 + 即时汇报**，或干脆后台化（第 4 条）+ 周期播报（第 6 条）。
- 同样适用于「检查状态」类操作：**单次状态检查控制在 ≤ 2 分钟**，超时先汇报再继续。

**12）用 `pkill` / `pgrep` 前先确认不会杀到自己**：

- **根因**：`pkill -f <pattern>` / `pgrep -f <pattern>` 匹配的是**完整命令行**，
  而你自己的那条命令里就含有该 pattern 字符串 → **会匹配到自己**（杀自己 → 会话中断；
  或列出自己 → 误判为“进程还在”）。
- **本会话反面案例（均实际发生）**：
  - `pkill -f install_cosyvoice_deps` → 把自己的 shell 杀了（exit 255）
  - `pgrep -f "comfy_win.ps1"` → 把自己的 powershell 也列进结果（误判“守护仍在”）
  - `pgrep -f "[i]nstall_"` 后跟一行列出文件名 “install_xxx.sh” → 同样自匹配
  - PowerShell 侧：`Get-CimInstance Win32_Process | Where CommandLine -like '*x*'` 也会命中**自己的 `-Command` 字符串**
- **强制做法（四选一，优先前两个）**：
  1. **字符类打断字面量**：`pgrep -f "[c]omfy_win"`、`pkill -f "[i]nstall_cosy"`
     （自己的命令行里是 `[c]omfy_win`，不等于正则 `comfy_win`→ 不自匹配）
  2. **按 PID 精确杀**：先用 `ss -ltnp` / `ps -eo pid,cmd` 取出 PID，再 `kill <pid>`；
     不要用模式匹配去杀
  3. **先 dry-run 列清单**：先跑 `pgrep -af <pattern>`，**人工看一眼里面有没有自己/无关进程**，再决定杀不杀
  4. PowerShell 侧：**把进程列表导出到文件，再在别处 grep**（避免过滤串出现在被查进程里）；
     或显式排除自身：`Where-Object { $_.ProcessId -ne $PID }`
- **额外注意（守护脚本）**：这类项目大量使用 `while($true)` 自重启守护（`comfy_win.ps1` / `tunnel_comfy.ps1` /
  `wsl_tts_win.ps1` / `worker_win.ps1`）。**必须先杀守护再杀服务**，否则服务 10 秒后就被拉回；
  且**心跳计划任务要一并禁用**（`*Heartbeat` 每 10 分钟会把守护拉回），禁用**不会**终止已排队实例——可能还会被拉一次。

---


---

## 〔归档 C2〕原 §0.3 上下文与输出纪律（全文）

### 0.3 上下文与输出纪律（2026-09-16 新增，必须逐条遵守）

> **事故背景**：单个会话连续跑了 5 天（09-11 → 09-16），会话文件涨到 **14.5 MB / 2389 条**，
> 其中 `toolResult` 占 **66%（9.77 MB）**，仅两条超大输出就占 48%（4.15 MB + 2.94 MB）——
> 全部是把 `psql jsonb_pretty`、`comfyui.log` 尾巴、下载器日志、`ls -l` 全表**原样倒进上下文**造成的。
> 后果：token 用量急剧上涨、频繁自动压缩、每次响应更慢更贵。（2026-09-16 用户明令写入本约定。）

**1）按任务开新会话**：一个会话只推进一个阶段；阶段完成即输出「状态快照」并换新会话。
禁止在一个会话里跨天堆多个不相关任务。

**2）禁止把原始 JSON / 日志倒进上下文**：远程命令一律只回**聚合或摘要** ——
`count(*)`、`left(col,120)`、`string_agg` 只取 3~5 个字段、`tail -n 3 | cut -c1-120`。
需要看全量就**写脚本落盘**（`/opt/weaveora/logs/...`），上下文里只留结论与文件路径。

**3）禁止写轮询循环**：长任务一律「后台脚本 + 日志文件」，agent 只做 **1~2 次**短查询；
单次等待 ≤ 60s；禁止 `sleep 300/600`、禁止 `while sleep` 反复轮询（同一条查询重复 N 次也算违规）。

**4）大文本走 context-mode 沙箱**：日志 / JSON / 源码的批量分析用 `ctx_execute_file` / `ctx_batch_execute`，
让原始字节留在沙箱，只把要用的字段带回；**禁止**用普通 `cat` / 无过滤 `grep` 拉大文件。

**5）长输出先落盘再摘要**：任何 > 2 KB 的产物先写文件，回复里只给「路径 + 关键数字」；
`ctx_batch_execute` 的命令输出也要保持短小（它会把命令输出回显进上下文）。


---

## 〔归档 C3〕原 §0 硬性指令 19 条（全文；规范正文已收口到 CLAUDE.md）

## 0. 给实现模型的硬性指令

1. **先读完全文，再写代码。** 不要只实现某一章。
2. **不要把 GPU 推理写进 Java。** Stable Diffusion / 视频生成必须跑在 Python Worker 上，Java 只做编排、鉴权、账单、资产与导出。
3. **所有生成必须经过「用户确认」闸门。** 禁止自动把 LLM 草稿直接送进 SD。确认动作要落库（谁、何时、哪一版 prompt）。
4. **图片流与视频流共用 Brief → PromptDraft → Confirm → Job → Asset，视频流额外插入 Script / Scene / Shot / EditPackage。**
5. **MVP 用 Spring Modulith 模块化单体。** 接口按微服务切面设计，等流量或团队规模需要时再按第 16.4 章拆服务。禁止第一天就上 8 个可独立部署的微服务。
6. **前端创作台以 Vue 3 Web 为唯一 MVP 交付面。** Flutter 只做二期移动审片，不要用 Flutter 做第一版故事板 / 提示词工作台。
7. **数据库只有 PostgreSQL（业务）+ Redis（缓存 / 锁 / 限流 / 验证码 / GPU Job 队列）+ 对象存储（媒体）。** 不要用 Mongo 存主数据，**MVP 不再引入 RabbitMQ**（v1.3 裁定，Job 队列用 Redis Streams）。
8. **所有异步 Job 必须可重试、可取消、可幂等。** 以 `idempotency_key` 为幂等键。
9. **密钥不进仓库、不进前端。** LLM / SD / OSS / 剪映凭据只存在环境变量 / 未提交的 profile 配置 / K8s Secret（MVP 无 Nacos，拆分服务后才引入）。
10. **本文第 30 章已锁定。** 若与代码冲突，停下来问产品，不要擅自改产品语义。
11. **UI 文案默认中文，提示词默认英文，API 字段英文。**
12. **本仓库若存在演示用前端，不得当成生产实现。** 生产后端是 Java 21 + Spring Boot 3.4.5（MVP 不依赖 Spring Cloud / Nacos / Gateway），生产前端是 `weaveora-web`（Vue 3）。
13. **禁止 `spring.jpa.hibernate.ddl-auto=update`。** 实体与 Flyway 双写，开发 / 生产均为 `validate`。不要学 MirrorTalk 的自动建表。
14. **禁止 `ConcurrentHashMap` 存验证码 / 登录失败计数 / IP 限流。** 一律 Redis。MirrorTalk 源码自己也写了「生产环境建议用 Redis」。
15. **禁止把 MirrorTalk 的 User 表、同库、配额字段直接拷进织影。** 只参考 JWT 无状态方案；用户 / 工作区 / 额度按本文重建。
16. **禁止用同步 HTTP 堵住 API 线程等 GPU 出图。** LLM 导演方案可以同步等 ≤60s；GenerationJob 必须进 **Redis Streams 工作队列**（消费组 + 独立死信流，v1.3 裁定替代 RabbitMQ）。
17. **ORM 锁定 Spring Data JPA。** 禁止再引入 MyBatis / MyBatis-Plus。
18. **文档正文不锁模型/引擎的具体版本；一切模型版本走 Model Preset / 配置项（§7.8/§23），由配置决定当前最优，正文只写能力档位。**（v1.9，应对模型与软件快速迭代：2026-09 已出现 DeepSeek V4、FLUX.1-Krea、Wan 2.5/2.6/2.7 等换代，锁死版本会让文档迅速过时。）
19. **大文件下载一律走「10 路 Range 分片 + 断点续传 + 后台静默 + 只出进度日志」，禁止任何会阻塞会话的长连接下载。** 详见 §0.2，实现模型必须逐条遵守（2026-09-13 因 `huggingface_hub.snapshot_download` 长连接把会话堵死而新增）。

---

## 〔归档 B5〕原 §0.1 如何高效使用本文档（已重写：行数/token 与阅读协议均已过期）

### 0.1 如何高效使用本文档（实现模型必读，v2.0）

本文档约 1900 行 / ~2 万 tokens。结构策略如下，避免上下文浪费与漏读风险：

1. **每个实现会话开始时完整读入一次本文档全文**（§0–§32；§33 为历史快照可跳过）。约 2 万 tokens 对现代模型上下文（≥128K）无压力，比“分片猜读”更安全。
2. **读完后按 §28 里程碑逐 PR 工作**；每个 PR 只回看与本任务相关的章节（如做 job 只回看 §17/§20/§19），**不要把整份文档反复贴回上下文**。
3. **§0（本条）与 §30 决策表是“约束总纲”**：与正文冲突时，§0/§30 优先；若与代码冲突，停下问产品（§0-10），不要擅改产品语义。
4. **§33 版本沿革 / §1.2 备选名 / §32 附录为可跳过区**：前两者是历史存档，附录仅在用到负面词种子/示例 Brief 时查。
5. **模型/引擎一律读 Model Preset 配置（§23）而非本文**：本文只写能力档位；具体版本看 Nacos/env 中 `weaveora.llm.model`、Model Preset 表，不要根据本文历史章节推断版本。
6. **需要外部事实（模型/价格/竞争）时检索最新信息，不要依赖本文或本模型的记忆**——AI 领域按周迭代，本文已明确不锁版本（§0-18）。

---


---

## 〔归档 B6〕原 §28.1 实现模型开工顺序（第一周文件级，已完成）

### 28.1 实现模型开工顺序（第一周文件级）

1. `api` 骨架 + Modulith 包 + Flyway V1 + JPA 实体  
2. identity：注册登录 JWT，验证码 Redis  
3. project CRUD（全部带 `workspace_id`）  
4. `web` 登录 + 项目列表 + 新建  
5. dev 可连中间件：PG 本机 `weaveora_dev` + Redis db1（本机临时实例或 VPS；v1.5）  
6. 再进入 director  

禁止第一周做：微服务拆分、Flutter、Seata、真实 ComfyUI、剪映私有格式逆向、H2、`ddl-auto=update`、内存验证码。

---

