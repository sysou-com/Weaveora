# Weaveora 织影 — 会话入口

> **本文件是薄索引，不是规范正文。** 唯一产品 / 架构真源 = **`Weaveora.md`**（v2.0，2272 行，约 3 万 tokens）。
> 本文件只负责「告诉实现模型去哪读、按什么顺序读」——不要在这里发明架构，也不要指望它替代真源。

---

## 一、开工协议（每个会话必做）

1. **先读文档，再写代码。不要只实现某一章。**（`Weaveora.md` §0-1）
2. **按任务分层读，禁止每会话无脑全文重读：**

| 任务类型 | 必读 |
|---|---|
| **任何新会话开场** | ① `docs/交接快照-*.md` 中 **mtime 最新**的一份（见下方注意）② `docs/知识档案索引.md` §0「阅读优先级」按场景路由到具体文件 |
| 涉及产品语义 / 架构 / 状态机 / API 契约 / 数据模型变动 | `Weaveora.md` **§0–§32 全文**（§33 版本沿革为历史快照，跳过） |
| 局部代码改动 / 修 bug | `Weaveora.md` §0（约束总纲）+ §30（已锁定决策）+ 与本任务相关的章节 |
| 仅看某模块细节 | 按 `docs/知识档案索引.md` §0 表格取对应专题文件，**不要**因此回读全文 |

> ⚠️ **注意：交接快照的文件名日期不可靠**（例：`交接快照-2026-09-16-夜.md` 的 mtime 反而最新，2026-09-17 12:49）。
> 一律用 `stat -c '%y  %n' docs/交接快照-*.md | sort -r` 取最新，不要按文件名猜。
> `docs/知识档案索引.md` 里写死的「目前是 xxx」也会漂移，以 mtime 为准。

3. **冲突优先级：** `Weaveora.md` **§0 / §30** > 正文其他章节 > 现有代码。
   与代码冲突时**停下问产品**，不要擅自改产品语义。（§0-10）
4. 阶段完成即输出「状态快照」并**换新会话**；禁止在一个会话里跨天堆多个不相关任务。（§0.3）

---

## 二、红线（摘要，仅为防呆；与 `Weaveora.md` §0 冲突时一律以 §0 为准）

- **⛔ 未经逐项确认，禁止删除 / 移动 / 覆盖任何文件、模型、数据、配置、记录。**
  - **判定“没人用”不构成授权。** `grep` 未命中、引用计数为 0、读代码推断“生产不可达”、体积大占地方——这些**只能当建议依据，不能当执行依据**。
  - 必须先：**列清单**（完整路径 + 体积 + 在文档/引擎配置里的角色 + 影响面 + 不可逆程度）→ **停下等用户逐项确认** → 才执行。
  - **适用范围与体积无关**：几十 MB 的配置/素材/数据同样要走这个流程；`rm`、`mv` 覆盖、`truncate`、数据库 `DELETE`/`UPDATE`、镜像/快照清理一律适用。
  - 执行前必须留下：操作清单文件（路径+体积+依据） + **恢复路径**（下载地址 / 备份文件名 / 备份表名），并在动手前把恢复命令贴给用户。
  - **事故记录（2026-09-19）**：助手仅凭“引用计数 + 读代码推断生产不可达”，自行删掉 7 个模型（含 `docs/gpu-模型清单与镜像备份.md` 标注为「**文生图主力**」的 `qwen_image_fp8_e4m3fn.safetensors`），合计 **79.5 GB**，且**未等用户确认就执行** —— 用户要求把这条写死，任何人（包括 AI）不得再犯。
- **⛔ 分析问题必须用「当前日期的最新信息」，不得靠记忆或过期文档下结论。**
  - 凡涉及外部事实（模型/版本/参数/API/兼容性/价格/发布时间）→ **先检索当下最新信息**（官方文档、官方仓库、发行说明、时间戳），再分析。
  - 结论必须能指出**来源 + 日期**；引用的资料看不到日期时，当“未核实”处理。仓库内文档也可能过期，同样要用上述优先级校验。
- **⛔ 确认信息以**官方**为准；用非官方信息必须标注并取得确认。**
  - 优先级：① **官方**（官方文档 / 官方仓库 README / 官方 workflow 模板 / 源码） > ② 官方社区与发行说明 > ③ 第三方（博客、论坛、模型站二手页、转载） > ④ 助手记忆与推测。
  - 凡来自 ③④ 的信息，**必须显式标注「非官方 / 未核实」并停下等用户确认**，不得当作事实写进代码、文档、配置或结论。
  - 事故记录（2026-09-19）：助手把“某站点页面 / 自己的推断”当成事实，先后定下“LoRA 必顶 cfg=1.0”（后来又说官方模板是 cfg=4，两个都没拿官方模板核实），以及“该模型生产不可达”（只看了 worker 模式选择、没查 API 空镜分支）→ 直接导致误删。用户要求写死这两条。
- **⛔ 大文件下载必须多路并行 + 断点续传（§0.2）**：单连接 curl 在大文件上会被服务端限速（2026-09-19 实测）。
- **GPU 推理不写进 Java**：Stable Diffusion / 视频生成只在 Python `worker/`；Java 只做编排 / 鉴权 / 账单 / 资产 / 导出。（§0-2）
- **所有生成必须过「用户确认」闸门**并落库（谁 / 何时 / 哪一版 prompt）；禁止 LLM 草稿直送 SD。（§0-3）
- **异步 Job 走 Redis Streams**（消费组 + 独立死信流），**MVP 不引入 RabbitMQ**；必须可重试 / 可取消，以 `idempotency_key` 幂等。（§0-7 / §0-8 / §0-16）
- **禁止 `spring.jpa.hibernate.ddl-auto=update`**：Flyway + 实体双写，开发 / 生产均为 `validate`。（§0-13）
- **禁止用 `ConcurrentHashMap` 存验证码 / 登录失败计数 / IP 限流**，一律 Redis。（§0-14）
- **ORM 锁定 Spring Data JPA**，禁止 MyBatis / MyBatis-Plus。（§0-17）
- **密钥不进仓库、不进前端**（LLM / SD / OSS 凭据只在环境变量或未提交的 profile）。（§0-9）
- **文案中文 / 提示词英文 / API 字段英文。**（§0-11）
- **模型版本不写死在文档或代码**，一律读 Model Preset / 配置（§7.8 / §23）；需要外部事实时检索最新信息。（§0-18）
- **大文件（单文件 ≥ 200 MiB）下载**只允许 `deploy/windows/gpu_model_downloader.js`（10 路 Range 分片 + 断点续传 + 后台静默 + 只出进度日志）；禁止任何阻塞会话的长连接下载。（§0.2）
- **禁止把会话堵死超过 60 秒（红线；2026-09-19 本会话我自己犯过）** —— `Weaveora.md §0.3` 第 **4 / 10 / 11** 条 + `§0-19`：
  - **单次等待 ≤ 60s**；禁止 `sleep 300` / `sleep 600` 这类死等；禁止 `while sleep` 反复轮询（**同一条查询重复 N 次也算违规**）。
  - 长任务一律 **`setsid … </dev/null >log 2>&1 &` 后台化**，启动命令必须**立即返回**；agent 不得等它跑完，只做 **1~2 次轻量短查询** + 每轮汇报一行。
  - **下载与其它任务并行**：不要「等它跑完再干别的」（第 10 条）。
  - **后果（用户点名的那条）**：挂起长连接 / 长 `sleep` 会把会话**堵死 → 无法并行处理任务**（用户这期间发的消息只能干等）；并存 **掩盖卡死**（等 10 分钟醒来才发现根本没开始跑）+ 无法中断 + 无法按中途变化改策略。
  - ⚠️ **反面案例（本会话实测）**：为等 GPU 出图连续用 `sleep 420` / `sleep 600`，把会话堵死十几分钟，用户多次无法插话 → **正确做法：后台化 + 短查询（≤60s）+ 即时汇报**。
- **上下文纪律**：不把原始 JSON / 日志倒进上下文；长输出先落盘再给「路径 + 关键数字」。（§0.3，细则见 `docs/notes/环境与纪律-总纲.md`）
- **不要从 MirrorTalk 拷贝实现**：User 表 / 同库 / 配额字段 / 自动建表均被点名禁止，只可参考 JWT 无状态方案。（§0-13 / §0-14 / §0-15）

---

## 三、仓库结构（§25；`app/` 为二期规划，尚未创建）

```
Weaveora/
  Weaveora.md              # 唯一产品 / 架构真源（v2.0）
  docs/                    # 交接快照 / 专题设计 / 经验与坑 / 知识档案索引
  api/                     # Java 21 + Spring Boot 3.4.5，Spring Modulith 模块化单体
  web/                     # Vue 3 创作台 —— MVP 唯一交付面
  worker/                  # Python GPU Worker（ComfyUI / 出图 / 对口型 / 音频）
  packages/schemas/        # 前后端共享契约 schema
  deploy/                  # compose / k8s / workflows / 运维脚本（含 gpu_model_downloader.js）
```

- 包名 `studio.weaveora.<module>`；Maven 用 `weaveora-parent` BOM。
- 数据库：PostgreSQL（业务）+ Redis（缓存 / 锁 / 限流 / 验证码 / GPU Job 队列）+ 对象存储（媒体）。
- **前端 MVP 交付面是 Vue 3 Web，不是 Flutter**（Flutter 仅二期移动审片）。（§0-6）

---

## 四、GPU 服务器重连协议（外网端口 / SSH 地址端口一变，或每次重连成功，必做）

> 端口每次都变是**常态**，不是故障。历史串（仅供理解“会变”这件事，**不要拿来当当前值**）：HTTP 侧 `10558→21270→12476→15276→…`；SSH 侧 `10532→15216/14812→31012→12424→15224→…`。
> ⛔ **纪律（2026-09-22 用户裁定）：GPU 地址/端口一律以「配置」为准，禁止写进文档当“当前值”。**
> 每次要用就现场读这几处（顺序=优先级）：① DB `user_engine_settings.gpu_server_url`+`gpu_server_port`（平台配置页写的就是它）→ ② VPS `/etc/weaveora/weaveora-gpu-worker.env` 的 `WEAVEORA_COMFY_URL` → ③ 盒上 `/system_stats` 的 `argv[0]`（身份判据）。
> 文档里只允许写**读取命令**，不允许写地址：
> ```bash
> ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select gpu_server_url, gpu_server_port, gpu_max_resolution, image_max_resolution from user_engine_settings where gpu_server_url is not null;\""
> ```
> **同一镜像的 ED25519 主机键不变**（实测 `SHA256:WEeKFd90sfhAztcvbci9bH98kReJlkMNgvlkskxTnJA`）→ **不能拿它判“是不是同一台”**。
> ⚠️ **`/root` 会随实例重置**：`authorized_keys` 与 root 密码**双双失效** → 每次换实例先重装公钥（`ssh-ed25519 AAAA…IJIAL8p/QXhatorBw/T5TOjmWl0aZW0Hh4IsRaM883ND gpu-server-20260913`）。
> **模型/权重清单与镜像要点 = `docs/gpu-模型清单与镜像备份.md`**（含全部能力 → 文件 → 字节 → 系统盘/addDisk）；
> 一键重扫 = `deploy/gpu_manifest.sh`（在新实例上 `bash gpu_manifest.sh > /tmp/gpu_manifest.txt`，与镜像里存的那份 diff）。
> 关键信息源 = **`GPU服务器能力搭建指南.md`**，**按锚点抽读、禁止通篇读**：
> `grep -n "addDisk\|软链\|services_up\|8001\|weaveora-stack" GPU服务器能力搭建指南.md`
> 只要拿到五件事：① 数据盘挂载点 ② 模型软链布局（§1.2 目录树）③ 服务矩阵与端口（§1.2）④ 第 6 章自检清单 ⑤ 坑表 **33 / 34 / 42**。
> ⚠️ **指南会滞后，以现网 argv 为准**：例 §1.2 写 `--cache-none`，而铁律③**禁** `--cache-none`，现网实际是 `--cache-ram 32`。

**1. 先确认「8001 上跑的到底是不是我们部署的 ComfyUI」——不确认就出图 = 白跑**
```bash
curl -s http://<GPU>:<HTTP端口>/system_stats | python3 -c 'import json,sys;print(json.load(sys.stdin)["system"]["argv"])'
```
判据：**`argv[0]` 必须是 `/opt/weaveora/ComfyUI/main.py`**。
→ 若是**系统自带 / 别的路径 / 别的实例**：**先停掉系统自带的 ComfyUI，再启我们自己部署的那套**（`/opt/weaveora/services_up.sh`，或 `systemctl restart weaveora-stack.service`），**然后才允许出图**。
**为什么必须**：不同 ComfyUI 实例的**模型搜索路径不同** → **同一个权重文件名会解析到不同的文件**，症状是「图出得来但完全不对（发黑 / 退化 / 不像定妆照）」，**且不报错**。2026-09-19 已实测出现过一次。

**2. addDisk 软链：每次重连都要验 —— 「静默错值」的头号来源**
```bash
df -h / /media/vipuser/addDisk
readlink -f /opt/weaveora/ComfyUI/models/diffusion_models     # 必须落到 addDisk
find / -name "qwen_image_edit_*.safetensors" -printf "%12s  %TY-%Tm-%Td  %p\n" 2>/dev/null
# ★ 同名权重出现 >1 份且大小不同 = 用错权重；软链断了 ComfyUI 会静默回退/读另一份
deploy/gpu2_post_maint_check.sh                               # 全量自检
```
数据盘挂载点会变（`/media/vipuser/addDisk` ⇄ `/addDisk`）→ 悬空软链靠**兼容软链**一条命令全修：
`mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk`（指南坑 33）

**3. 重启/维护一律走护栏**：`deploy/gpu2_restart.sh`（先查库 queued/running 就拦，`FORCE=1` 才强推）。直连 `systemctl restart` 会撞死正在跑的任务（指南坑 42）。重启后必查 5 个端口 `8800/8001/8091/8093/8094`（坑 34：talk 启动块丢过）。

**4. 换端口要同步的地方（一处都不能漏，并回读确认）**
`~/.ssh/config` 三个别名（`gpu`/`gpu2`/`weaveora-gpu-a14b`）→ 平台「生成引擎配置 → GPU 服务器地址+端口」→ `services.*` 里的**显式 URL（只有清空才能跟随 GPU 地址）**→ VPS `/etc/weaveora/weaveora-gpu-worker.env` 的 fallback。
⚠️ **平台配置页会把旧快照写回**（已发生：把 `12476` 洗回已失效的 `21270`，出图直接 `Connection refused`）→ **每次保存后必须回读**：
```bash
# 一条就够（services 整个 jsonb 回读，自己看有没有残留旧端口）
ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select gpu_server_port, services from user_engine_settings where gpu_server_url is not null;\""
```

**5. 判「配置对不对」只看两条日志**（worker）：`服务地址：comfy=http://<gpu>:<port>` 与 `工作流出图：<wf>(edit|img2img|txt2img) size=… refs=…`。
**出图异常时的第一件事不是调参数，是逐字节对照**：同一条 prompt 的 `md5(positive_prompt)` 在「正常那次」与「异常那次」是否一致（`assets`+`generation_jobs` 可查）。一致 → 问题在盒/环境侧；不一致 → 问题在 prompt/计划侧。

**6. 盒子底账（2026-09-19 实测，省得每次重查）**
```
ComfyUI 目录（只有第一个是我们的）
  /opt/weaveora/ComfyUI/main.py   我们部署，v0.34.0 tag(2026-08-25)，服务 :8001 ← 必须它
  /root/ComfyUI/main.py           系统自带，v0.27.0（未跑则不管；跑了就停它）
确认： ps -ef | grep "[m]ain.py"   —— 只应有一条
软链： /opt/weaveora/ComfyUI/models -> /opt/weaveora/models 。。。/media/vipuser/addDisk -> /addDisk
权重（diffusion_models 无同名多副本，都已核实）
  qwen_image_edit_2511_fp8mixed -> /addDisk/…  20,533,762,817 B  （带 _quantization_metadata = fp8mixed）
  qwen_image_edit_fp8_e4m3fn    -> /media/vipuser/addDisk/…  20,430,635,136 B
  qwen_image_fp8_e4m3fn / wan2.2_i2v_{high,low}_noise = **系统盘真文件**
  text_encoders/vae 里 qwen_2.5_vl_7b_fp8_scaled / qwen_image_vae / umt5_xxl / wan_2.1_vae = **系统盘真文件**
  upscale_models = **空**（要放大得先下 ESRGAN 类权重）
判同不同一个模型：读 safetensors 头（fp8mixed 带 _quantization_metadata、tensors≈4451；base 无 metadata、tensors=1933）
```

**7. ★ 出图变暗/退化的头号开关是 `steps` / `cfg`（2026-09-19 定位，别再去查模型/软链）**
`services.image.steps` / `cfg` 会被 worker 注入到 **edit 工作流的 KSampler**。
官方口径 = **40 步 / cfg 4.0**（`docs/research/qwen-image-edit-official.md:24`）。
实测（同 prompt / 同参考图 / 同尺寸 1280×704 / 同工作流，唯一变量 steps·cfg）：**40/4.0 → 亮度 71–93（正常）；20/3.5 → 亮度 0–21（近全黑）**。
⇒ **为提速降步数前先看这条：代价可能是整幅退化，不是“稍软”。**
