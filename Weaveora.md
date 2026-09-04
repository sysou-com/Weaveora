# Weaveora 织影

**产品与技术设计规格书 · v1.3**  
状态：**选型已锁定，v1.3 裁定已确认**（2026-09-04，按 MirrorTalk 代码实测复核 + 你逐条确认的 7 项裁定）  
日期：2026-09-04  
文档用途：唯一产品 / 架构真源。实现模型先读完全文再写代码，不另发明架构。

> **v1.3 变更要点（2026-09-04，你逐条确认）：** ① 出图通道 = 自建 ComfyUI + 后期 GPU 机（**MirrorTalk 现有出图通道不变**）；② MVP 队列 = **Redis Streams**（不再引入 RabbitMQ）；③ MVP **不上 Nacos / Gateway / Sentinel**；④ 版本 = **Java 21 + Spring Boot 3.4.5 + Spring Modulith 1.3.x**；⑤ 存储 = dev 本地目录适配器 + **生产阿里云 OSS**；⑥ 额度 = MVP 提供 `simplified` 开关；⑦ 里程碑按**单人实施**重排。详见 §15 / §23 / §28 / §30。

---

## 目录

0. 给实现模型的硬性指令  
1. 命名与品牌  
2. 要解决的问题  
3. 目标用户  
4. 产品原则  
5. 版本范围  
6. 核心用户旅程（含时序图）  
7. 功能规格  
8. 信息架构  
9. 关键交互  
10. AI 导演层  
11. Stable Diffusion 与视频生成  
12. 剪映 / CapCut 导出  
13. 领域模型  
14. 数据库设计  
15. 技术选型（**已锁定**，含 MirrorTalk 实测对齐）  
16. 系统架构与模块  
17. API 契约  
18. 前端设计  
19. GPU Worker  
20. 状态机  
21. 存储与媒体  
22. 安全、配额、审计  
23. 配置与部署  
24. 可观测性  
25. 仓库与目录  
26. 编码规范  
27. 测试  
28. 实施里程碑  
29. 风险  
30. **已锁定决策**  
31. 演示与验收  
32. 附录  

---

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

---

## 1. 命名与品牌

### 1.1 选定名称（已锁定）

| 项 | 值 |
| --- | --- |
| 中文名 | 织影 |
| 英文名 | Weaveora |
| 读音 | wee-VOH-rah |
| 域名建议 | weaveora.studio / weaveora.cn |
| 包名 | `studio.weaveora.*` |
| Maven groupId | `studio.weaveora` |
| 仓库 | `weaveora` monorepo，或 `weaveora-platform` / `weaveora-web` / `weaveora-worker` / `weaveora-app` |

**命名理由**

- **织**：把一句话、风格、镜头、音效织成一条可执行的制作线，而不是一次「碰运气出图」。
- **影**：同时覆盖静帧（影像）与短片（影）。
- **ora**：光晕 / aurora 的词根，对应生成后的画面。
- 与「对话类」产品（如 MirrorTalk）形成系列感：Talk 是对话，Weave 是编织成片。

**一句话定位**

> Weaveora 织影是面向创作者的 **AI 导演台**：用户只给粗需求，系统产出可编辑的提示词 / 剧本 / 分镜，**经人工确认后**交给 Stable Diffusion 出图或出短片，再导出给剪映做精修，确认后交付成片。

**副标题**

- 中文：把一句话，织成画面与短片
- 英文：From a sentence to a shot.

### 1.2 备选名（已不采用；若品牌层改名，架构不变）

| 名称 | 调性 | 不选原因 |
| --- | --- | --- |
| CineLoom 影织 | 更「电影工业」 | 拼写易被读成 cine-loom 工具名，品牌延展弱 |
| DirectorLoom 导影 | 强调导演 | 「导」字在中文互联网易与导航 / 导购撞车 |
| LuminaForge 光铸 | 更「引擎」 | Forge 在开发者圈过载 |
| SceneSmith 场景匠 | 更「手作」 | 国际名一般，中文名一般 |
| Frameweaver 织帧 | 直白 | 中文名略硬，英文过长 |

### 1.3 品牌视觉（实现必须遵守）

- 放映厅暗色：近黑暖底 `#0B0B0A`，骨白字 `#F3F0E8`，低饱和青绿强调 `#8FB9B4`。
- 次级文字 `#A39E93`，分割线 `#24221F`，危险 `#C45C4A`，成功 `#7AA87A`。
- **禁止** 紫 / 品红 / 霓虹赛博、大面积渐变、emoji 图标、金黄主色、默认 Element Plus 蓝。
- 字体：标题衬线（Fraunces / 思源宋体 Noto Serif SC），正文无衬线（Figtree / 思源黑体 Noto Sans SC），等宽 IBM Plex Mono。
- 标志：片门圆角矩形 + 一条织线穿过。单色，16px 仍可识别。
- 圆角：控件 8px，卡片 12px，弹层 16px。
- 动效：150–200ms ease，禁止弹跳和彩带动画。

---

## 2. 要解决的问题

今天用 SD / ComfyUI / 可灵 / 即梦的真实痛点不是「没有模型」，而是：

1. **用户不会写提示词。** 一句话需求变成可用的 SD 正 / 负向提示、镜头、光、构图，中间缺一层「导演」。
2. **视频比图片更缺结构。** 没有剧本、场次、镜头时长、运镜、角色一致性，模型只会出互不相关的片段。
3. **人机没有闸门。** 一次生成烧掉 GPU 与额度，回改成本高。
4. **生成与精修断裂。** 出了片段还要手动对齐时间线、字幕、BGM，才能进剪映。
5. **工程上 Java 业务中台与 Python GPU 世界分裂。** 需要一条可审计的生产管线，而不是笔记本里的 workflow.json。

Weaveora 不做「又一个模型广场」，做 **导演层 + 确认闸门 + 生成编排 + 精修导出**。

---

## 3. 目标用户

| 角色 | 诉求 | 成功标准 |
| --- | --- | --- |
| 短视频创作者 / 中小 MCN | 用口语需求快速出可用素材 | 30 分钟内从一句话到可进剪映的草稿时间线 |
| 电商视觉 / 独立设计师 | 固定风格反复出图 | 风格模板可复用，种子 / LoRA / 参数可锁定 |
| 广告 / 品牌小团队 | 先分镜再拍或先分镜再生成 | 分镜表可评审、可导出 |
| 个人创作者 | 低门槛出一张海报或 15s 短片 | 不需要会写 SD 语法 |

**非目标（MVP 不做）**

- 实时直播连麦数字人
- 长视频（>60s）自动成片
- 替代剪映的完整 NLE
- 训练用户私有大模型（可挂 LoRA，但不提供训练平台）
- 社区 / 广场 / 作品公开 Feed

---

## 4. 产品原则

1. **人是导演，模型是摄影组。** AI 给方案，人点确认。
2. **先结构，后像素。** 视频必须先有剧本和镜头表，再生成。
3. **每一次生成都贵，所以默认可编辑、可 diff、可回滚。**
4. **风格是资产。** 提示词、LoRA、负面词、种子、镜头语言沉淀为可复用模板。
5. **导出是一等公民。** 生成完若不能进剪映，任务不算完成。
6. **可解释。** 用户能看到「为什么会写成这条 prompt」（镜头、风格、禁则拆开显示）。

---

## 5. 版本范围

### 5.1 MVP（v0.9，6–8 周，可演示全链路）

- 邮箱登录（手机号验证码可选），项目、草稿
- 图片链路：口语 Brief → LLM 提示词 → 人工确认 → SD 出图 → 选图 / 再生成
- 视频链路：口语 Brief → LLM 剧本 + 分镜 + 逐镜提示词 → 人工确认 → 逐镜出关键帧 → 时间线拼装 → 导出剪映草稿包
- 风格模板 8 个内置（电影静帧、产品广告、水墨、日漫、纪录片、赛博夜景、柔光静物、建筑空间）
- 额度、任务队列、失败重试
- Web 创作台（Vue 3）
- 管理端最小集：模型开关、队列、用户冻结

### 5.2 v1.0

- 角色 / 场景一致性（IP-Adapter / 参考图 / 种子锁定）
- 镜头级视频（SVD / AnimateDiff / Hunyuan / CogVideo 可插拔）
- 旁白 TTS、字幕轨、BGM 推荐
- 团队空间、评论、审片
- 剪映草稿更完整（转场、关键帧、字幕样式）
- 管理后台：模型、队列、成本看板

### 5.3 v1.5+

- Flutter 移动端审片 / 出图确认
- 自动粗剪（根据剧本切镜头、配字幕）
- 工作流市场（ComfyUI workflow 上架）
- 多模型路由（SDXL / Flux / 可灵 / 即梦 / Runway 适配器）
- 品牌套装（Logo、字体、色板注入提示词）
- 微信登录

---

## 6. 核心用户旅程

### 6.1 图片

```
登录 → 新建项目 → 选择「图片」
  → 输入口语需求（可上传参考图）
  → 选择风格模板 / 比例 / 张数
  → AI 导演产出：正向提示、负向提示、参数建议、中文解释
  → 用户改词 / 锁镜头 / 锁风格 → 确认
  → 创建 GenerationJob → GPU Worker 调 SD
  → 多图候选 → 用户挑图 / 局部重绘 / 放大
  → 入库 Asset → 下载或送入视频项目当关键帧
```

```mermaid
sequenceDiagram
  actor U as 用户
  participant W as Vue 创作台
  participant A as weaveora-api
  participant L as LLM
  participant Q as Redis Streams
  participant P as Python Worker
  participant S as ComfyUI / SD
  participant O as OSS

  U->>W: 输入口语 Brief
  W->>A: POST /briefs + POST /director/generate
  A->>L: completeJson(schema)
  L-->>A: 提示词 JSON
  A-->>W: PromptRevision v1
  U->>W: 改词并确认
  W->>A: POST /revisions/{id}/approve
  W->>A: POST /jobs (kind=still)
  A->>A: 额度预扣 + insert job
  A->>Q: JobQueued
  Q->>P: consume
  P->>S: workflow sdxl_txt2img
  S-->>P: png
  P->>O: put object
  P->>A: POST /internal/jobs/{id}/complete
  A->>A: 结算额度 + 写 Asset
  A-->>W: WS job.succeeded
  U->>W: 下载 / 收藏
```

### 6.2 短视频

```
登录 → 新建项目 → 选择「短视频」
  → 输入口语需求 + 时长（6 / 10 / 15 / 30s）+ 画幅（9:16 / 16:9 / 1:1）
  → AI 导演产出：
       一句话 logline
       剧本（起承转合，按秒）
       场次与镜头表（景别、运镜、动作、对白、音效）
       每镜 SD / 视频提示词 + 负面词 + 一致性约束
       剪映时间线草案（轨、入出点、转场）
  → 分镜墙：用户改某一镜，可只重生成该镜
  → 确认全片或确认单镜
  → Worker 按镜生成（静帧预可视化 → 可选视频片段）
  → 时间线预览（Web 粗剪）
  → 导出 Jianying Edit Package
  → 用户在剪映精修
  → 回传成片（可选）→ 项目完成
```

```mermaid
sequenceDiagram
  actor U as 用户
  participant W as Vue 创作台
  participant A as weaveora-api
  participant L as LLM
  participant P as Worker
  participant J as 剪映

  U->>W: Brief「12 秒纸船雨夜」
  W->>A: director/generate
  A->>L: 剧本 + 分镜 JSON
  L-->>A: shots[n], sum(duration)=12s
  A-->>W: Revision + ShotDrafts
  U->>W: 改第 2 镜提示词
  U->>W: 确认全片
  W->>A: approve revision
  W->>A: jobs kind=still（每镜）
  A->>P: 逐镜 txt2img
  P-->>W: 关键帧
  U->>W: 满意后（v1）jobs kind=motion
  A->>P: img2vid
  U->>W: 导出
  W->>A: POST /exports
  A-->>U: zip（edit_list + 媒体 + README）
  U->>J: 导入精修
```

闸门：**LLM 输出 ≠ 已确认。** 状态机见第 20 章。

---

## 7. 功能规格

### 7.1 账号与工作区

- 注册 / 登录：邮箱 + 密码为 MVP 必做；手机号 + 验证码可同期；微信为 v1.5。
- 工作区（Workspace）：个人默认一间；v1 支持成员与角色（Owner / Editor / Reviewer / Viewer）。
- 额度：按「LLM token / 出图张数 / 视频秒数 / 存储 GB」计量，记在 **CreditWallet**，不要做 MirrorTalk 那种挂在 User 上的三级配额字段。
- 项目：名称、模式（image | video | mixed）、画幅、风格、状态。

### 7.2 Brief（粗需求）

| 字段 | 约束 |
| --- | --- |
| `raw_text` | 必填，10–2000 字 |
| `mode` | `image` \| `video` \| `auto`（LLM 分类，用户可改） |
| `duration_sec` | 仅视频：6 / 10 / 15 / 30 |
| `aspect_ratio` | `1:1` `3:2` `2:3` `16:9` `9:16` |
| `style_template_id` | 可选 |
| `reference_asset_ids[]` | 参考图，最多 4 |
| `constraints` | 必须出现 / 禁止出现 / 品牌色 / 语言 |
| `count` | 图片张数 1 / 2 / 4，默认 2 |

### 7.3 AI 导演（Prompt Studio）

对图片输出：

| 字段 | 说明 |
| --- | --- |
| title | 短标题 |
| logline | 一句话画面 |
| positive_prompt | 英文为主、可含质量词，面向 SD |
| negative_prompt | 标准负面 + 用户禁则 |
| prompt_zh | 中文解释，给不懂 SD 的人看 |
| camera | 焦距、机位、景别 |
| lighting | 光型 |
| palette | 3–5 色 |
| params | sampler, steps, cfg, width, height, seed 建议 |
| variations | 2–3 个风格变体，用户可切换 |

对视频额外输出：

- `script.acts[]`：段、时长、目的
- `scenes[]` / `shots[]`：见领域模型
- `audio`：BGM 情绪、SFX、VO 文案
- `edit_plan`：轨结构、转场、字幕

### 7.4 确认与版本

- 每次 LLM 产出是一个 `PromptRevision`（diff 可看）。
- 用户编辑后另存为新 revision，不覆盖。
- 「确认生成」把某 revision 钉成 `approved_revision_id`。
- 允许「只确认第 3 镜」。
- 未确认时，前端主按钮禁用，API 返回 `REVISION_NOT_APPROVED`。

### 7.5 生成

- 图片：1 / 2 / 4 张；支持同种子对比 sampler。
- 视频：先出每镜关键帧（便宜），用户满意后再出运动片段（贵）。
- 默认策略：**预可视化（still）→ 确认 → 运动（clip）**。
- 失败：把 ComfyUI / SD 错误转成可读原因（OOM、NSFW、超时、模型未加载）。

### 7.6 资产

- 原图、缩略图、视频 mp4、预览 webp、prompt 快照、seed、模型哈希一并保存。
- 可收藏、可打分、可设为参考图。

### 7.7 导出剪映

导出 zip，内含：

```
{project_name}_weaveora_edit/
  README.md
  edit_list.json
  jianying/
    draft_content.json
    draft_meta_info.json
    assets/
  captions.srt
  voiceover.txt
  prompts.md
```

**不允许**假装能一键控制用户本机剪映。MVP 是「导出草稿包 + 导入说明」。

### 7.8 模板与模型

- Style Template：提示词前缀 / 后缀、负面、推荐 sampler、LoRA 列表。
- Model Preset：SDXL / Flux / SVD 等，指向 Worker 里的 ComfyUI workflow。
- 管理员可热更新，不发版。

### 7.9 MVP 内置风格模板

| slug | 名称 | 用途 |
| --- | --- | --- |
| `cinematic-still` | 电影静帧 | 低饱和、变形宽银幕感、实用光 |
| `product-ad` | 产品广告 | 干净背景、柔和棚灯、材质清晰 |
| `ink-wash` | 水墨 | 宣纸、焦墨、留白 |
| `jp-anime` | 日漫 | 赛璐璐、清晰线、二次元光 |
| `documentary` | 纪录片 | 自然光、手持感、真实纹理 |
| `cyber-night` | 赛博夜景 | 湿路面、青橙对比、霓虹克制使用 |
| `soft-still-life` | 柔光静物 | 窗光、浅景深、静物，不出人脸 |
| `architecture` | 建筑空间 | 超广角、体积光、材质真实 |

默认 **关闭「真人模特」类模板**。用户 Brief 明确要求人物时，导演层用非可识别面孔（远景、背影、剪影）。

---

## 8. 信息架构（Web）

```
/login
/app                          项目列表
/app/projects/new             新建（选图片或视频）
/app/projects/:id             项目总览
/app/projects/:id/brief       粗需求
/app/projects/:id/director    提示词 / 剧本工作室（核心）
/app/projects/:id/board       分镜墙
/app/projects/:id/generate    任务与进度
/app/projects/:id/gallery     资产
/app/projects/:id/timeline    粗剪时间线（视频）
/app/projects/:id/export      导出
/app/styles                   风格模板
/app/settings                 账号、额度
/admin                        模型、队列、用户、成本
```

移动端（Flutter 二期）只做：项目列表、确认生成、画廊、通知。

---

## 9. 关键交互细节

### 9.1 导演台（必须做成左右栏）

- 左：Brief 原文 + 约束，始终可见。
- 中：结构化方案（剧本、镜头、提示词），可逐段内联编辑。
- 右：参数、风格、参考图、费用预估。
- 底：版本时间条（v1、v2、v3…）和「确认并生成」主按钮。
- 费用预估在按钮旁：**约 N 张图 / M 秒视频 / x 积分**，点下去才扣。

### 9.2 分镜墙

- 每张卡片：镜号、时长、缩略图、状态（草稿 / 已确认 / 生成中 / 完成 / 失败）。
- 拖拽改顺序，自动重算时间码。
- 单卡「重写提示词」「只生成这一镜」。

### 9.3 生成中

- WebSocket 推进度（queued / loading_model / sampling 37% / uploading / done）。
- 允许取消；取消必须真正 kill worker 任务，不能只改 UI。

### 9.4 空状态

- 新项目：三张示例 Brief（「一只纸船穿越城市雨夜的 12 秒短片」「青瓷器物静物海报」「被水淹的巴洛克图书馆」）。
- 点示例即填入，降低白屏恐惧。

### 9.5 权限与按钮态

| 条件 | 确认并生成 | 导出 |
| --- | --- | --- |
| 无 revision | 禁用 | 禁用 |
| 有 revision 未 approve | 禁用（提示先确认） | 禁用 |
| 已 approve 无 asset | 可点 | 禁用 |
| 有 still assets | 可再生成 | 视频项目可导出静帧时间线 |
| 额度不足 | 禁用并说明 | — |

---

## 10. AI 导演层

### 10.1 模型分工

| 任务 | 推荐 | 备注 |
| --- | --- | --- |
| Brief 分类、补全、中文剧本 | DeepSeek-V3 / 通义 / GPT-4.1 / Grok | JSON schema 强制 |
| 英文化 SD 提示词 | 同一 LLM，用专用 system prompt | 不要用翻译腔 |
| 分镜时长分配 | LLM + 规则校验器 | 时长总和必须 == 用户指定，误差 ≤ 0.5s |
| NSFW / 品牌安全 | 规则 + 分类模型 | 拦截在确认前 |
| 多模态理解参考图 | 带视觉的 LLM | 把参考图特征写入约束 |

统一抽象：`LlmClient.completeJson(JsonSchema schema, List<Message> messages)`。  
全部走 **OpenAI Compatible** `baseUrl + apiKey + model`。模型名放配置中心或配置项，不写死（MVP 用 Spring profile / 环境变量）。

**与 MirrorTalk 的差异：** MirrorTalk 用 `WebClient` 同步 block 等 AI 结果。织影的 **director/generate 允许同步等 ≤60s**（用户在等方案）；**禁止**用同样方式等 GPU。

### 10.2 输出必须是 JSON，禁止散文

图片 schema（字段名稳定，作为 API 契约）：

```json
{
  "mode": "image",
  "title": "Flooded Library",
  "logline": "Moonlight over a drowned baroque library.",
  "prompt_zh": "月光从穹顶砸进被水淹没的巴洛克图书馆，无人，只有漂浮的书页。",
  "positive_prompt": "flooded baroque library at night, single moonlight shaft, floating books, still water mirror, cinematic still, 35mm, no people",
  "negative_prompt": "people, text, watermark, logo, subtitle, blurry, lowres",
  "camera": { "focal_mm": 35, "shot_size": "wide", "angle": "low" },
  "lighting": "single moonlight shaft, teal bounce",
  "palette": ["#0B1C22", "#C7B7A3", "#8FB9B4"],
  "params": {
    "width": 1216, "height": 832, "steps": 30, "cfg": 5.5,
    "sampler": "dpmpp_2m_karras", "seed": null
  },
  "variations": []
}
```

视频 schema：

```json
{
  "mode": "video",
  "title": "Paper Boat",
  "logline": "A paper boat crosses a city in rain.",
  "duration_sec": 12,
  "aspect_ratio": "16:9",
  "script": {
    "theme": "孤独与穿行",
    "acts": [
      { "name": "setup", "start_sec": 0, "end_sec": 3, "purpose": "建立水面与纸船" }
    ]
  },
  "shots": [
    {
      "shot_no": 1,
      "duration_sec": 3.0,
      "shot_size": "wide",
      "camera_move": "slow dolly in",
      "action": "paper boat drifts on glassy dusk water",
      "positive_prompt": "paper boat on dark rainy city canal, dusk, cinematic, 35mm",
      "negative_prompt": "people, text, watermark",
      "seed_lock": true,
      "ref_shot_no": null
    }
  ],
  "audio": {
    "music_mood": "sparse piano, wet atmosphere",
    "sfx": ["distant rain", "soft water lap"],
    "vo": ""
  },
  "edit_plan": {
    "fps": 30,
    "transition_default": "cut",
    "subtitle": false
  }
}
```

对应 Java record 放在 `studio.weaveora.director.api` 包，前后端共享同一份 JSON Schema 文件：`packages/schemas/director.schema.json`。

### 10.3 规则校验（代码，不靠模型自觉）

类：`DirectorPlanValidator`（纯函数，单测覆盖）。

- `sum(shot.duration_sec) == duration_sec ± 0.5`
- 每个 shot 必须有 `positive_prompt` 且长度 20–1200
- `negative_prompt` 自动合并模板负面词（去重）
- 禁止 shot 数量 > `ceil(duration_sec / 1.5)`
- 视频默认 4–8 镜 / 12 秒
- 画幅映射到偶数宽高（SD 要求 8 的倍数，推荐 64 的倍数）
- JSON 解析失败则重试 1 次，仍失败则返回可编辑的原文并标 `DIRECTOR_PARSE_FAILED`
- 命中 NSFW 词表 → `BRIEF_BLOCKED`，不落可生成 revision

### 10.4 System prompt 要点

实现时放入 `director/src/main/resources/prompts/`，不写死在代码字符串散落各处。

- 你是电影摄影指导 + 分镜师，不是聊天机器人。
- 面向 Stable Diffusion XL / Flux：主语 + 场景 + 光线 + 镜头 + 风格 + 质量。
- 不要堆 20 个质量词；3 个以内。
- 用户没要求文字，则 negative 必须含 `text, watermark, logo, subtitle`。
- 用户没要求真人，则不要发明可识别人脸。
- 中文 Brief 可以保留专有名词，提示词用英文。
- 输出且只输出 JSON。

文件：

```
prompts/director_image_system.md
prompts/director_video_system.md
prompts/director_rewrite_shot.md
prompts/brief_classify.md
```

---

## 11. Stable Diffusion 与视频生成

### 11.1 原则：ComfyUI 为执行引擎

Java 不直接调 diffusers。Worker 暴露：

```
POST /worker/v1/jobs
GET  /worker/v1/jobs/{id}
POST /worker/v1/jobs/{id}/cancel
```

鉴权：HMAC `X-Weaveora-Signature`（`timestamp + jobId + sha256(body)`）。

Job payload 引用 `workflow_id` + 变量映射（prompt、seed、width、image refs）。  
主路径是 **Redis Streams 消费**，上述 HTTP 仅供运维探活 / 单测。

### 11.2 内置 workflow（Worker 仓库）

| ID | 用途 | MVP |
| --- | --- | --- |
| `sdxl_txt2img` | 默认出图 | 必做 |
| `flux_txt2img` | 高质量出图 | 可选 |
| `sdxl_img2img` | 参考图 / 重绘 | 必做 |
| `ipadapter_ref` | 角色 / 物体一致性 | v1 |
| `upscale_4x` | 放大 | v1 |
| `svd_img2vid` | 关键帧 → 短运动 | v1 |
| `animatediff_txt2vid` | 文本短视频 | 可选 |
| `hunyuan_txt2vid` | 高质量短视频 | 可选 |
| `stub_txt2img` | 开发占位图 | 必做 |

模型文件 **不进 git**。

### 11.3 视频两段式（强制，已锁定）

1. **Still pass**：每镜 1 张关键帧，便宜、利于改词。
2. **Motion pass**：用户确认关键帧后再 img2vid / txt2vid。

禁止 MVP 一上来就对未确认剧本烧视频秒数。

### 11.4 安全

- 提示词过 NSFW 词表 + 可选 CLIP 分类。
- 输出再扫一次。命中则资产标记 `blocked`，不对用户展示原图，记审计。
- 用户重复打安全策略则冻账号。

### 11.5 画幅 → 像素

| 比例 | 图片默认 | 视频默认 |
| --- | --- | --- |
| 1:1 | 1024×1024 | 1024×1024 |
| 3:2 | 1216×832 | 1920×1280 预览用 768×512 |
| 2:3 | 832×1216 | 768×1152 |
| 16:9 | 1344×768 | 1920×1080（生成可用 1280×720） |
| 9:16 | 768×1344 | 1080×1920（生成可用 720×1280） |

---

## 12. 剪映 / CapCut 导出

### 12.1 `edit_list.json`（Weaveora 自己的稳定契约）

```json
{
  "version": "1.0",
  "project_id": "…",
  "title": "Paper Boat",
  "fps": 30,
  "width": 1920,
  "height": 1080,
  "duration_sec": 12,
  "tracks": [
    {
      "type": "video",
      "clips": [
        {
          "shot_no": 1,
          "asset_id": "…",
          "src": "assets/shot_01.mp4",
          "in_sec": 0,
          "out_sec": 3,
          "timeline_start_sec": 0
        }
      ]
    },
    { "type": "audio", "clips": [] },
    { "type": "caption", "clips": [] }
  ]
}
```

JSON Schema 文件：`packages/schemas/edit_list.schema.json`。  
剪映 `draft_content.json` 是不稳定私有格式，用 **适配器** 生成，失败时至少保证 `edit_list.json` + 媒体文件 + README 可用。

```java
public interface EditAdapter {
  String vendor(); // "jianying" | "generic"
  void write(Path dir, EditList list, List<ResolvedAsset> assets) throws ExportException;
}
```

### 12.2 README 必须告诉剪辑师

- 画幅与帧率、镜号对应文件名、建议的转场、VO 文案
- 「这些片段由 Weaveora 生成，精修在剪映完成」
- 若 jianying 适配失败：写明「请按 edit_list.json 手工导入」

---

## 13. 领域模型

```
Workspace 1──n Project
Workspace 1──n Membership (v1)
Project 1──n Brief
Brief 1──n PromptRevision
PromptRevision 1──n ShotDraft
Project 1──n GenerationJob
GenerationJob n──1 PromptRevision (or ShotDraft)
GenerationJob 1──n Asset
Project 1──n EditPackage
StyleTemplate / ModelPreset  (全局或工作区)
CreditWallet 1──n CreditLedger
AuditLog
```

| 实体 | 职责 |
| --- | --- |
| **Workspace** | 计费与隔离边界 |
| **Project** | 一次创作任务的容器 |
| **Brief** | 用户口语需求快照 |
| **PromptRevision** | AI 或用户产生的某一版方案 |
| **ShotDraft** | 视频镜头行 |
| **GenerationJob** | 一次 GPU 执行 |
| **Asset** | 一张图或一段视频 |
| **EditPackage** | 一次导出 |
| **CreditWallet** | 工作区余额 |

```mermaid
classDiagram
  Workspace "1" --> "*" Project
  Project "1" --> "*" Brief
  Project "1" --> "*" PromptRevision
  PromptRevision "1" --> "*" ShotDraft
  Project "1" --> "*" GenerationJob
  PromptRevision "1" --> "*" GenerationJob
  ShotDraft "0..1" --> "*" GenerationJob
  GenerationJob "1" --> "*" Asset
  Project "1" --> "*" EditPackage
  Workspace "1" --> "1" CreditWallet
  CreditWallet "1" --> "*" CreditLedger
```

---

## 14. 数据库设计（PostgreSQL 16）

约定：

- PK 一律 `UUID`，应用侧 **UUIDv7**。
- 时间一律 `timestamptz`，时区 UTC 存储。
- 软删 `deleted_at`。
- JSONB 仅用于确实会变的结构（params、camera、edit_plan），镜头表仍用关系表。
- 所有业务表带 `workspace_id`（除全局字典 `model_presets`、系统 `style_templates`）。
- 迁移工具 **Flyway**，脚本 `V1__init.sql`、`V2__credits.sql`…。JPA 实体与脚本字段必须一致。
- `spring.jpa.hibernate.ddl-auto=validate`（开发与生产相同）。**禁止 `update`。**
- ORM：**Spring Data JPA + Hibernate 6**（对齐 MirrorTalk）。查询以派生方法 + `@Query` JPQL 为主。实体放各模块 `domain` 包，禁止跨模块引用对方实体。
- 开发库用 Compose PostgreSQL 或 Testcontainers，**不要用 H2**（JSONB / citext / 部分索引与 H2 行为不同）。

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE TABLE workspaces (
  id            uuid PRIMARY KEY,
  name          text NOT NULL,
  owner_user_id uuid NOT NULL,
  plan          text NOT NULL DEFAULT 'free',
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

CREATE TABLE users (
  id            uuid PRIMARY KEY,
  email         citext UNIQUE,
  phone         text UNIQUE,
  password_hash text,
  display_name  text NOT NULL,
  status        text NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

CREATE TABLE memberships (
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  user_id       uuid NOT NULL REFERENCES users(id),
  role          text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE credit_wallets (
  workspace_id  uuid PRIMARY KEY REFERENCES workspaces(id),
  balance       numeric(12,4) NOT NULL DEFAULT 0,
  frozen        numeric(12,4) NOT NULL DEFAULT 0,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_wallet_nonneg CHECK (balance >= 0 AND frozen >= 0)
);

CREATE TABLE style_templates (
  id            uuid PRIMARY KEY,
  workspace_id  uuid REFERENCES workspaces(id),
  slug          text NOT NULL,
  name          text NOT NULL,
  prompt_prefix text NOT NULL DEFAULT '',
  prompt_suffix text NOT NULL DEFAULT '',
  negative      text NOT NULL DEFAULT '',
  default_params jsonb NOT NULL DEFAULT '{}',
  is_system     boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE model_presets (
  id            uuid PRIMARY KEY,
  slug          text NOT NULL UNIQUE,
  kind          text NOT NULL,
  workflow_id   text NOT NULL,
  display_name  text NOT NULL,
  cost_unit     text NOT NULL,
  cost_credits  numeric(12,4) NOT NULL,
  enabled       boolean NOT NULL DEFAULT true
);

CREATE TABLE projects (
  id                    uuid PRIMARY KEY,
  workspace_id          uuid NOT NULL REFERENCES workspaces(id),
  created_by            uuid NOT NULL REFERENCES users(id),
  title                 text NOT NULL,
  mode                  text NOT NULL,
  aspect_ratio          text NOT NULL,
  duration_sec          numeric(6,2),
  style_template_id     uuid REFERENCES style_templates(id),
  status                text NOT NULL,
  approved_revision_id  uuid,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  deleted_at            timestamptz
);

CREATE TABLE briefs (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  raw_text      text NOT NULL,
  mode          text NOT NULL,
  constraints   jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prompt_revisions (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  brief_id      uuid NOT NULL REFERENCES briefs(id),
  revision_no   int NOT NULL,
  source        text NOT NULL,
  schema_json   jsonb NOT NULL,
  title         text,
  logline       text,
  positive_prompt text,
  negative_prompt text,
  created_by    uuid REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (project_id, revision_no)
);

CREATE TABLE shot_drafts (
  id            uuid PRIMARY KEY,
  revision_id   uuid NOT NULL REFERENCES prompt_revisions(id) ON DELETE CASCADE,
  shot_no       int NOT NULL,
  duration_sec  numeric(6,2) NOT NULL,
  shot_size     text,
  camera_move   text,
  action        text,
  positive_prompt text NOT NULL,
  negative_prompt text NOT NULL,
  seed_lock     boolean NOT NULL DEFAULT true,
  ref_shot_no   int,
  status        text NOT NULL DEFAULT 'draft',
  UNIQUE (revision_id, shot_no)
);

CREATE TABLE generation_jobs (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES projects(id),
  workspace_id    uuid NOT NULL REFERENCES workspaces(id),
  revision_id     uuid REFERENCES prompt_revisions(id),
  shot_id         uuid REFERENCES shot_drafts(id),
  model_preset_id uuid NOT NULL REFERENCES model_presets(id),
  kind            text NOT NULL,
  state           text NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  payload         jsonb NOT NULL,
  progress        int NOT NULL DEFAULT 0,
  stage           text,
  error_code      text,
  error_message   text,
  credits_reserved numeric(12,4) NOT NULL DEFAULT 0,
  credits_settled  numeric(12,4) NOT NULL DEFAULT 0,
  worker_id       text,
  created_by      uuid NOT NULL REFERENCES users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  started_at      timestamptz,
  finished_at     timestamptz
);

CREATE TABLE assets (
  id            uuid PRIMARY KEY,
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  project_id    uuid NOT NULL REFERENCES projects(id),
  job_id        uuid REFERENCES generation_jobs(id),
  shot_id       uuid REFERENCES shot_drafts(id),
  kind          text NOT NULL,
  storage_key   text NOT NULL,
  thumb_key     text,
  mime          text NOT NULL,
  width         int,
  height        int,
  duration_ms   int,
  seed          bigint,
  model_hash    text,
  prompt_snapshot jsonb,
  nsfw          boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE edit_packages (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  revision_id   uuid NOT NULL REFERENCES prompt_revisions(id),
  storage_key   text NOT NULL,
  edit_list     jsonb NOT NULL,
  created_by    uuid NOT NULL REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credit_ledger (
  id            uuid PRIMARY KEY,
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  job_id        uuid REFERENCES generation_jobs(id),
  delta         numeric(12,4) NOT NULL,
  balance_after numeric(12,4) NOT NULL,
  reason        text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
  id            uuid PRIMARY KEY,
  workspace_id  uuid,
  user_id       uuid,
  action        text NOT NULL,
  entity_type   text NOT NULL,
  entity_id     uuid,
  meta          jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_ws ON projects(workspace_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_jobs_state ON generation_jobs(state, created_at);
CREATE INDEX idx_jobs_ws ON generation_jobs(workspace_id, created_at DESC);
CREATE INDEX idx_assets_project ON assets(project_id, created_at DESC);
CREATE INDEX idx_revisions_project ON prompt_revisions(project_id, revision_no);
CREATE INDEX idx_audit_ws ON audit_logs(workspace_id, created_at DESC);
CREATE INDEX idx_users_email ON users(email);
```

注册时：创建 `users` + `workspaces` + `memberships(owner)` + `credit_wallets`（赠送新用户额度），同一事务。

`projects.approved_revision_id` 在 Flyway V2 用 `ALTER TABLE ... ADD CONSTRAINT fk_approved_revision` 补外键，避免循环创建问题。

JPA 映射要点：

- 实体类（不要用 record 当 `@Entity`）。
- UUID 用 `uuid` 类型；`citext` 列用 `String` + 列定义。
- JSONB 用 `JsonNode` 或 dedicated `@JdbcTypeCode(SqlTypes.JSON)`。
- Repository：`interface ProjectRepository extends JpaRepository<Project, UUID>`，工作区隔离写成 `findByWorkspaceIdAndIdAndDeletedAtIsNull`。
- 额度预扣必须用 `@Modifying @Query` 带 `WHERE balance - frozen >= :c`，检查 `updated row count`。

---

## 15. 技术选型（已锁定）

2026-09-02 按 MirrorTalk **代码实测** 对齐，并经你确认；2026-09-04 v1.3 裁定修订（队列 / 基建 / 版本 / 存储）。实现模型不得再改 ORM / 队列 / 前端选型。

### 15.0 MirrorTalk 实测摘要（决策依据）

| 项 | MirrorTalk 现状 | 织影怎么做 |
| --- | --- | --- |
| ORM | Spring Data JPA（Hibernate），41 个实体（实测，文档早期写 30），`ddl-auto: update`，无 MyBatis、无 Flyway | **跟 JPA**；**不跟 `ddl-auto: update`**，改 Flyway + `validate` |
| 队列 | 无 AMQP/Kafka/Rabbit/Redis MQ 客户端。AI 调用 `WebClient` 同步 block | **新上 Redis Streams**（工作队列，v1.3 裁定替代 RabbitMQ）。LLM 导演方案可同步等 60s；**GPU Job 禁止同步 block** |
| Redis | 无。验证码 / 登录失败 / IP 发信计数在 `ConcurrentHashMap`（作者已注明生产该用 Redis） | **第一天就上 Redis 7**（缓存 / 锁 / 验证码 / Streams 队列 / WS pubsub） |
| 用户中心 | 单体嵌入：`AuthService` + `JwtUtil` + `JwtAuthFilter` + `User`（email/passwordHash/nickname/三级配额…），`userId` 散落 30 张表，无独立边界 | **参考 JWT 无状态**；**新建** User / Workspace / Membership / CreditWallet。不同库、不拷贝配额字段 |
| 定时 | `@EnableScheduling` 进程内 | 分片清理等可保留 `@Scheduled`；生成任务不走定时器 |
| 前端 | Flutter 无 ORM，Isar 已移除，仅 shared_preferences + flutter_secure_storage | MVP 是 Vue 3 Web；Flutter 二期同样只用安全存储，不引入 Isar |

### 15.1 锁定架构

| 层 | 锁定值 | 理由 |
| --- | --- | --- |
| 创作台 Web | **Vue 3.5 + TypeScript + Vite + Pinia + Vue Router + Naive UI + TanStack Vue Query** | 导演台 / 分镜墙是桌面级密集 UI |
| 移动审片 | **Flutter 3 + Riverpod + GoRouter + Dio**（二期） | 审片、推送、确认生成；**不要**用 Flutter Web 当创作台 |
| API 中台 | **Java 21 + Spring Boot 3.4.5**（MVP 不引 Spring Cloud / Nacos / Gateway） | Java 团队习惯；**Spring Cloud 组件留到真正拆服务时再上**（v1.3 裁定） |
| MVP 形态 | **Spring Modulith 1.3.x 模块化单体 `weaveora-api`** | MirrorTalk 也是单体；织影同样单体，但模块边界写清 |
| GPU Worker | **Python 3.11 + FastAPI + ComfyUI**（自建，GPU 机后续搭建） | SD 生态在 Python。**MirrorTalk 现有出图通道（Replicate）保持不变** |
| 队列 | **Redis Streams**（Redis 7 内建，消费组 + 独立死信流） | MirrorTalk 没有 MQ 可跟；Redis 第一天就上。GPU 任务要重试、可取消、多 Worker，Streams 消费组足够（v1.3 裁定替代 RabbitMQ） |
| 缓存 / 锁 / 验证码 | **Redis 7** | 进度、限流、分布式锁、WS pub/sub、验证码、Job 队列。不复制内存 Map |
| 数据库 | **PostgreSQL 16**（本机 18.4 实测兼容，validate 只比对结构） | 与 MirrorTalk 生产库同族 |
| 对象存储 | **dev：本地目录适配器（LocalFileStorageAdapter）**；生产：**阿里云 OSS**（参考 MirrorTalk 既有 aliyun-sdk-oss 用法） | 媒体。v1.3 裁定：不引 MinIO，dev 期直接用本地目录，生产直接 OSS |
| 实时进度 | **Spring WebSocket + Redis Pub/Sub** | 已确认不改 SSE |
| 配置 / 发现 | **MVP：Spring profile + 环境变量**（镜像 MirrorTalk 生产 systemd Environment 方式） | 单体无需 Nacos；**拆服务后才引入 Nacos**（v1.3 裁定） |
| 网关 | **MVP 用同进程 Spring Security filter**；拆服务后才是 Spring Cloud Gateway | JWT、限流、路由（v1.3 裁定：MVP 不部署独立网关） |
| ORM | **Spring Data JPA + Hibernate 6** | 对齐 MirrorTalk，团队零迁移成本 |
| 迁移 | **Flyway 10** | 不跟 MirrorTalk 的 `ddl-auto: update` |
| 鉴权 | Spring Security + JWT（access 15m + refresh 14d，旋转 refresh） | 参考 MirrorTalk `JwtUtil` / `JwtAuthFilter` 重写，不拷贝 User 实体 |
| 参数校验 | Jakarta Validation | |
| JSON | Jackson + 共享 JSON Schema | |
| 观测 | Micrometer + Prometheus + Grafana + Loki + OpenTelemetry | Worker 同样暴露 `/metrics` |
| 部署 | Docker Compose（开发） / K8s（生产） | GPU 节点独立 taint |

### 15.2 相对 MirrorTalk 的差异（有意为之，不是疏忽）

| # | 织影 | MirrorTalk | 为什么必须不同 |
| --- | --- | --- | --- |
| A | Vue 3 Web MVP，Flutter 二期 | 以 Flutter 为主 | 导演台 / 分镜 / 时间线 Web 更合适 |
| B | Spring Modulith 单体 | 单体 | 形态相同，织影把包边界写死，方便以后拆 |
| C | Python Worker | 无 GPU | SD 不能进 JVM |
| D | **Redis Streams 工作队列** | 无 MQ，AI 同步 block | 出图 / 出视频是分钟级，占 HTTP 线程会拖垮 API。Redis 本就必上，Streams 免新增中间件 |
| E | Naive UI 创作台 | — | 避免中后台皮肤 |
| F | 额度预扣 + 状态机，不上 Seata | — | 单库事务足够 |
| G | WebSocket 进度 | — | Job 百分比 |
| H | ComfyUI 为主 | — | 提示词 / 种子 / LoRA 可审计 |
| I | **Redis 存验证码与限流** | ConcurrentHashMap | 重启失效、多实例互不认识 |
| J | **Flyway + ddl-auto=validate** | ddl-auto=update | 生成管线表有额度与状态机，不能靠 Hibernate 改表 |
| K | Workspace 隔离 + CreditWallet | User 上三级配额字段 | 计费边界是工作区，不是聊天用户 |

### 15.3 不推荐（实现禁止）

| 选择 | 原因 |
| --- | --- |
| 纯 Flutter 做第一版创作台 | 提示词 diff、分镜拖拽、时间线在 Web 上成熟一个数量级 |
| 在 JVM 里跑 ONNX SD | 生态、插件、LoRA、ControlNet 全在 Python |
| Mongo 作主库 | 镜头、额度、任务状态是强关系 + 事务 |
| 第一天就上 Seata | 用「额度预扣 + 本地状态机 + 幂等」即可 |
| 用 Element Plus 默认主题直接上创作台 | 会做成后台管理系统 |
| 用 XXL-JOB 跑出图 | 它是定时调度，不是 GPU 队列 |
| 用 Kafka / RabbitMQ 当默认队列 | 运维重；Redis Streams 消费组 + 死信流已够 MVP |
| 把 LLM 调用做成独立微服务 | 只有一个 HTTP client，抽 module 即可 |
| **MyBatis-Plus** | 已锁定 JPA，禁止双 ORM |
| **同步 HTTP 调 Worker 等到出图** | 会把 MirrorTalk 的 block 习惯带进 GPU，API 必炸 |
| **`ddl-auto=update`** | 生产状态机表不可靠 |
| **内存 Map 验证码** | 重启丢、多实例错 |

### 15.4 若以后拆 Spring Cloud 微服务

允许，但服务数锁死为：

1. `weaveora-gateway`
2. `weaveora-auth`
3. `weaveora-core`（project / brief / revision / asset / export）
4. `weaveora-job`（队列编排）
5. `weaveora-worker`（Python，独立）

不要再拆 user / prompt / notify / asset 四个「只有 CRUD」的服务。  
模块包名保持 `studio.weaveora.<module>`，拆分时按包搬迁。  
**MVP 不拆。** MirrorTalk 也是单体，织影第一天同样一个 jar。届时才引入 Nacos / Gateway / Sentinel 与独立网关。

### 15.5 与 MirrorTalk 的复用边界（按实测收紧）

| 可参考（思路 / 代码片段） | 不可复用 |
| --- | --- |
| `JwtUtil` + `JwtAuthFilter` 无状态 JWT，`userId` 注入 request | `User` 实体及 `modelLevel/storageLevel/imageLevel/hiddenMenus/traits` |
| 邮件验证码登录流程（搬到 Redis） | `ConcurrentHashMap` 验证码 / 登录失败计数 / IP 发信计数 |
| 密码哈希、`disabled` 冻号思路 | 同库同进程、`userId` 散落到业务表当唯一隔离 |
| PostgreSQL + JPA 派生查询 + `@Query` JPQL 风格 | `ddl-auto: update`、H2 当开发库（织影开发用本机 PG / Compose PG + Testcontainers，不用 H2） |
| `@Scheduled` 做清理类任务 | 用调度器跑生成 Job |
| 注销级联思路（织影改为工作区匿名化） | AccountDeletion 直接改 30 张聊天表 |
| 管理台冻号 | UserAdmin 的聊天专用字段 |
| 无 | Flutter Isar / 本地 DB（已移除，不要捡回来） |

**没有可直接接入的用户中心。** 最干净的抄法是：重写 `AuthController` + `AuthService` + `JwtUtil` + `SecurityConfig` 这组（约 5–6 个文件），签名密钥新生成，验证码走 Redis，用户模型用本文第 14 章。

### 15.6 版本钉扎（实现按此，允许小版本上浮）

```
Java                 21 (Temurin)
Spring Boot          3.4.5
Spring Modulith      1.3.x
Spring Data JPA      Boot 托管（Hibernate 6）
Flyway               10.x
PostgreSQL           16（本机 18.4 亦可用）
Redis                7.2（验证码 / 限流 / 锁 / WS / **Job 队列 Streams**）
RabbitMQ             不引入（v1.3 裁定）
Nacos                MVP 不引入（拆服务后才用）
Vue                  3.5
Vite                 6
Node                 22
Python               3.11
FastAPI              0.115
Flutter              3.24+（二期）
```

---

## 16. 系统架构与模块

### 16.1 容器图

```mermaid
flowchart LR
  subgraph Client
    Vue[Vue 3 创作台]
    Flutter[Flutter 审片 二期]
  end

  API[weaveora-api  Modulith]
  PG[(PostgreSQL)]
  REDIS[(Redis 7)]
  OSS[(阿里云 OSS / dev 本地目录)]
  LLM[LLM OpenAI-compatible]
  WK[Python Worker]
  COMFY[ComfyUI GPU 机]

  Vue --> API
  Flutter --> API
  API --> PG
  API --> REDIS
  API --> OSS
  API --> LLM
  API -->|Redis Streams| WK
  WK --> COMFY
  WK --> OSS
  WK --> API
```

### 16.2 后端模块（Spring Modulith，包即边界）

```
weaveora-api
  studio.weaveora
    identity/      用户、工作区、JWT、membership；验证码走 Redis
    catalog/       风格模板、模型预设
    project/       项目、Brief
    director/      LLM 调用、revision、校验器
    job/           状态机、投递 Redis Streams、回执
    asset/         对象存储（StoragePort：dev 本地目录 / 生产 OSS）、缩略图、签名 URL
    export/        edit_list + 剪映适配器
    billing/       额度预扣 / 结算 / 释放
    admin/         管理端
    shared/        错误码、Id 生成、时钟、审计
  infra/
    streams/       Redis Streams 生产 / 消费 / 死信
    redis/
    ws/
    llm/
    storage/       StoragePort + LocalFileStorageAdapter + OssStorageAdapter
```

Modulith 规则：

- 模块间 **只通过 `*.api` 包的接口 / 事件** 通信，禁止跨模块抢 Repository。
- 领域事件：`RevisionApproved`、`JobQueued`、`JobSucceeded`、`CreditsReserved`。
- 单测用 `@ApplicationModuleTest`。

Python：

```
weaveora-worker
  app/api.py
  app/hmac.py
  app/comfy_client.py
  app/stub.py
  app/workflows/*.json
  app/safety.py
  app/storage.py
  app/stream_consumer.py   # Redis Streams 消费（替代 mq_consumer）
```

### 16.3 模块依赖

```
identity ← project ← director
                 ← job ← asset
                      ← billing
                 ← export
catalog ← director, job
shared ← *
```

禁止 `director` 直接调 Worker；只发 `JobQueued`。

### 16.4 拆服务触发条件（不要提前）

同时满足再拆：

- GPU Job 与 API 扩缩比 > 5×
- 团队 > 8 人且模块冲突明显
- 需要独立发布 Worker 契约以外的 Java 服务

---

## 17. API 契约

前缀 `/api/v1`，JSON，UTF-8。

统一错误：

```json
{ "code": "REVISION_NOT_APPROVED", "message": "尚未确认该版本", "traceId": "…" }
```

鉴权：`Authorization: Bearer <access>`。  
幂等：写操作可带 `Idempotency-Key`。  
分页：`?page=1&size=20`，响应 `{ "items": [], "total": 0, "page": 1, "size": 20 }`。

### 17.1 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `UNAUTHENTICATED` | 401 | 未登录 / token 失效 |
| `FORBIDDEN` | 403 | 工作区无权限 |
| `NOT_FOUND` | 404 | 资源不存在或不在本工作区 |
| `VALIDATION` | 400 | 参数不合法 |
| `BRIEF_TOO_SHORT` | 400 | Brief < 10 字 |
| `BRIEF_BLOCKED` | 422 | 安全策略拦截 |
| `DIRECTOR_PARSE_FAILED` | 422 | LLM JSON 不可用 |
| `REVISION_NOT_APPROVED` | 409 | 未确认就生成 |
| `SHOT_NOT_APPROVED` | 409 | 单镜未确认 |
| `JOB_NOT_CANCELLABLE` | 409 | 已进入不可取消阶段 |
| `INSUFFICIENT_CREDITS` | 402 | 额度不足 |
| `IDEMPOTENT_REPLAY` | 200 | 返回原 job |
| `NSFW_OUTPUT` | 422 | 输出被拦 |
| `WORKER_UNAVAILABLE` | 503 | 无可用 GPU |
| `EXPORT_EMPTY` | 409 | 无资产可导出 |
| `RATE_LIMITED` | 429 | 限流 |

业务异常类：`BizException(ErrorCode code)`，由 `@RestControllerAdvice` 转 JSON。

### 17.2 主要端点

```
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout

GET    /api/v1/me
GET    /api/v1/me/wallet

GET    /api/v1/projects
POST   /api/v1/projects
GET    /api/v1/projects/{id}
PATCH  /api/v1/projects/{id}

POST   /api/v1/projects/{id}/briefs
POST   /api/v1/projects/{id}/director/generate
GET    /api/v1/projects/{id}/revisions
GET    /api/v1/projects/{id}/revisions/{rid}
PATCH  /api/v1/projects/{id}/revisions/{rid}
POST   /api/v1/projects/{id}/revisions/{rid}/approve
POST   /api/v1/projects/{id}/shots/{shotId}/approve

POST   /api/v1/projects/{id}/jobs
GET    /api/v1/jobs/{jobId}
POST   /api/v1/jobs/{jobId}/cancel

GET    /api/v1/projects/{id}/assets
GET    /api/v1/assets/{id}/download

POST   /api/v1/projects/{id}/exports
GET    /api/v1/exports/{id}/download

GET    /api/v1/styles
GET    /api/v1/models

WS     /api/v1/ws
```

内部（仅 Worker，网关不暴露）：

```
POST /internal/jobs/{id}/progress
POST /internal/jobs/{id}/complete
POST /internal/jobs/{id}/fail
```

### 17.3 请求 / 响应示例

`POST /api/v1/projects`

```json
{
  "title": "雨夜纸船",
  "mode": "video",
  "aspectRatio": "16:9",
  "durationSec": 12,
  "styleTemplateId": null
}
```

`POST /api/v1/projects/{id}/director/generate`

```json
{ "briefId": "…", "mode": "video" }
```

响应：完整 `DirectorPlan` + `revisionId` + `revisionNo`。

`POST /api/v1/projects/{id}/jobs`

```json
{
  "revisionId": "…",
  "shotId": null,
  "kind": "still",
  "count": 1,
  "modelPresetId": null
}
```

语义：

- `shotId == null` 且视频：为所有 **已 approve** 的 shot 各建一个 job（批量）。
- 图片：对 revision 的正负提示出 `count` 张。

### 17.4 `POST /director/generate` 语义

- 同步等待 LLM（超时 60s）。
- 成功写入 `prompt_revisions` + `shot_drafts`，返回完整 schema。
- **不创建 GPU Job。**
- 把项目状态从 `draft` 推到 `directing`。

### 17.5 `POST /jobs` 语义

- 必须已 approve。
- 事务内：校验额度 → `credit_wallets.frozen +=` → `credit_ledger` reserve → insert job queued → 事务提交后 XADD 到 Redis Streams（避免消息先于事务提交 / 回滚不一致）。
- 回执 succeed：`frozen -=`，`balance -=`，ledger settle。
- fail / cancel：`frozen -=`，ledger release。
- `idempotency_key` 优先客户端；否则服务端 `sha256(workspace|project|revision|shot|kind|payloadCanon)`。

### 17.6 WebSocket 协议

连接：`WS /api/v1/ws?projectId=`，先鉴权。

服务端推送：

```json
{
  "type": "job.progress",
  "jobId": "…",
  "projectId": "…",
  "state": "running",
  "stage": "sampling",
  "progress": 37
}
```

`type` 枚举：`job.queued` | `job.progress` | `job.succeeded` | `job.failed` | `job.cancelled`。

实现：API 收到 Worker 回执 → Redis `PUBLISH weaveora.ws.{workspaceId}` → 本机 WS 会话转发。多实例安全。

### 17.7 Worker 回执

```
POST /internal/jobs/{id}/progress   { "progress": 37, "stage": "sampling" }
POST /internal/jobs/{id}/complete   { "assets": [ { "key", "width", "height", "seed", "mime", "durationMs" } ] }
POST /internal/jobs/{id}/fail       { "code", "message" }
```

HMAC 校验失败 → 401。状态非法迁移 → 409，Worker 记日志并丢弃。

---

## 18. 前端设计

### 18.1 Vue 3 创作台（MVP 必做）

技术：

- Vue 3.5 + `<script setup>` + TypeScript
- Vite 6
- Pinia（项目级 store + job store）
- Vue Router
- TanStack Vue Query（服务端状态）
- Naive UI 或 Arco Design Vue（暗色主题按 1.3 覆写）
- 提示词编辑：textarea + 行号
- 分镜墙：vuedraggable
- 时间线：轻量自研（不要引入完整剪辑器）
- 视频预览：`<video>` + 自定义控件
- 图标：Lucide
- 字体：Fraunces + Figtree + Noto Sans SC / Noto Serif SC

目录：

```
weaveora-web/
  src/
    api/
    stores/
    modules/
      projects/
      director/
      board/
      gallery/
      timeline/
      export/
    components/ui/
    layouts/
    styles/tokens.css
    schemas/
```

视觉：暗色放映厅，青绿只用于主按钮和「进行中」指示。

路由守卫：无 token → `/login`；项目级请求带当前 `workspaceId`（header `X-Workspace-Id`）。

### 18.2 Flutter（v1.5，本文只锁边界）

```
weaveora-app/
  lib/
    api/
    features/
      auth/
      projects/
      review/
      gallery/
```

- 功能：登录、项目列表、推送、对 revision 点「确认生成」、看图、看短预览。
- 禁止在 Flutter 里做提示词大编辑器、分镜拖拽、时间线。
- 本地只要 `shared_preferences` + `flutter_secure_storage`（对齐 MirrorTalk 现状），不要引 Isar。
- 同一套 `/api/v1`。

### 18.3 管理后台

与创作台同仓不同 layout：`/admin`。MVP 用同一 Vue 应用，路由 + 角色守卫。不要另起一个 Element Admin 模板仓库。

---

## 19. GPU Worker

- FastAPI + uvicorn
- 每个 GPU 进程绑一张卡（`CUDA_VISIBLE_DEVICES`）
- 从 **Redis Streams** 消费（消费组）Job，调 ComfyUI HTTP 或 stub
- 上传 OSS，回调 Java
- 心跳：Redis `worker:{id}` TTL 30s
- 优雅停机：停领新任务，当前任务可完成或标 retry

开发期 **必须** 有 CPU stub worker：根据 prompt hash 返回占位 PNG（Pillow 渲染标题 + 镜号）。环境变量 `WEAVEORA_WORKER_MODE=stub|comfy`。

取消：Java 发 `JobCancel` → Worker 调 ComfyUI interrupt；若已 uploading，则尽力取消上传但仍可能 complete——API 以 **先到的终态为准**，迟到的 complete 若 state=cancelled 则忽略并删除对象。

---

## 20. 状态机

### 20.1 Project.status

`draft → directing → approved → generating → reviewing → exported → archived`

允许回退：`reviewing → directing`（改词）、`generating → approved`（全失败）。

### 20.2 GenerationJob.state

`queued → running → succeeded | failed | cancelled`

`failed` 可 `retry` 生成 **新** job，不原地改历史。

### 20.3 ShotDraft.status

`draft → approved → generating → done | failed`

项目级 approve 会批量把所有 shot 置 approved。

```mermaid
stateDiagram-v2
  [*] --> draft
  draft --> directing: LLM 产出 revision
  directing --> approved: 用户确认
  approved --> generating: 创建 job
  generating --> reviewing: 全部 succeeded
  generating --> approved: 全部 failed/cancelled
  reviewing --> directing: 再改词
  reviewing --> exported: 导出
  exported --> archived
```

---

## 21. 存储与媒体

- 原文件：`s3://weaveora/{workspace}/{project}/{job}/{asset}.png|mp4`
- 缩略图：同前缀 `_thumb.webp`（最长边 512）
- 私有桶 + 短时签名 URL（10–30 分钟）
- 视频：h264 + aac，兼容剪映
- 图片：png 下载 + webp 预览
- 导出 zip 同桶 `…/exports/{exportId}.zip`

### 21.1 存储抽象（v1.3 裁定）

定义 `StoragePort` 接口（put / get / presign / delete）：

- dev / 单测：**`LocalFileStorageAdapter`** → 配置目录（如 `./data/storage`），不进 git。
- 生产：**`OssStorageAdapter`**（阿里云 OSS，参考 MirrorTalk 既有 `aliyun-sdk-oss` 用法，不引 MinIO / AWS SDK）。

导出 zip 临时落本地 `Files.createTempDirectory`，完成后删除。生成产物始终走 StoragePort，禁止 API 把业务文件落本地磁盘当生产存储。

---

## 22. 安全、配额、审计

- 密码 **Argon2id**（若 MirrorTalk 现用 BCrypt，织影仍用 Argon2id；不要混用）。
- 验证码 60s 频控、同一邮箱 10 次 / 小时，**存 Redis**，TTL 5–10 分钟。
- 登录失败计数 Redis，超阈值锁 15 分钟。
- 工作区隔离：所有查询带 `workspace_id`，**禁止前端传 userId 作为授权依据**。从 JWT 取 `userId`，从 header 取 `workspaceId` 并校验 membership。
- 额度预扣防止超卖：`UPDATE credit_wallets SET frozen = frozen + :c WHERE workspace_id = :w AND balance - frozen >= :c`，rows=0 则失败。
- 提示词与资产审计 180 天。
- SSRF：Worker 拉参考图只允许 OSS 白名单 host。
- 上传 MIME 与大小限制（图 20MB，视频 200MB）。
- 限流（MVP 用 Redis 计数 + 自定义注解，不引 Sentinel；拆服务后才上 Sentinel）：登录 5/s/IP，director/generate 3/min/user，jobs 10/min/user。
- 默认关闭公开分享链接。

### 22.1 默认额度（可被 profile / 环境变量覆盖）

| 套餐 | 赠送 | 图 | 视频 still | 视频 motion / 秒 |
| --- | --- | --- | --- | --- |
| free | 100 | 4 | 6 | 20 |
| creator | 1000 | 4 | 6 | 20 |
| team | 按充值 | 3 | 5 | 15 |

LLM 每次 director/generate 扣 1 积分（free 套餐每日最多 20 次另计）。

### 22.2 额度简化模式（v1.3 裁定）

- 配置开关 `weaveora.billing.mode=wallet|simplified`（默认 `wallet`）。
- `simplified`（MVP 内测 / 演示）：不做 wallet 预扣与 ledger，只做**配额常量校验**（图片张数上限、视频秒数上限、`director` 调用限频），全部免费，便于先跑通全链路。
- `wallet`（生产）：按第 17.5 / 20 章做预扣、冻结、结算、幂等。两模式共享同一 Job 状态机与确认闸门。

---

## 23. 配置与部署

### 23.1 配置（MVP：profile + 环境变量；拆服务后才用 Nacos）

```
application-{dev|staging|prod}.yml + 环境变量
  weaveora.llm.base-url
  weaveora.llm.api-key        (env: WEAVEORA_LLM_API_KEY)
  weaveora.llm.model
  weaveora.oss.access-key     (env: WEAVEORA_OSS_*)   # 生产阿里云 OSS
  weaveora.oss.bucket
  weaveora.storage.mode: local|oss     # local=LocalFileStorageAdapter, oss=OssStorageAdapter
  weaveora.storage.local-dir: ./data/storage
  weaveora.queue.job-stream: weaveora:jobs
  weaveora.queue.dl-stream:  weaveora:jobs:dlq
  weaveora.billing.mode: wallet|simplified
  weaveora.credits.image: 4
  weaveora.security.jwt.access-ttl: 15m
  spring.jpa.hibernate.ddl-auto: validate
  spring.flyway.enabled: true
```

密钥不进仓库：dev 用未提交的 `application-local.yml`（参考 MirrorTalk `application-local.example.yml` 模式）；生产用环境变量 / K8s Secret。

### 23.2 开发 Compose

服务：`postgres` `redis` `api` `web` `worker-stub`（MVP 无 rabbitmq / minio / nacos；本机无 Docker 时可用本机 PG / 单独起的 Redis，配 `application-local.yml`）

一键：`docker compose -f deploy/compose/dev.yml up`（若本机有 Docker）。  
`make bootstrap`：建库、Flyway、种子风格模板、种子管理员。

### 23.3 生产

- MVP 部署形态：单机 systemd（镜像 MirrorTalk 生产方式）：`weaveora-api`（CPU）+ `worker-stub`（先跑通）→ GPU 机到位后换 `weaveora-worker`（ComfyUI）。
- 扩展形态（拆服务后才编排）：`gateway` / `api` CPU 节点 HPA；`worker` GPU 节点按队列深度扩；独立命名空间 `weaveora`。
- 配置：环境变量 + 进程 manager（systemd Environment / K8s Secret），不用 Nacos（拆服务后才引入）。
- 备份：PG 每日 + WAL；OSS 跨区域复制。
- 网络策略：Worker 可出网拉模型，但 ComfyUI 端口不对公网。

---

## 24. 可观测性

- TraceId 从入口 filter 注入（`X-Trace-Id`；拆服务后才由 Gateway 注入），贯穿 LLM 与 Worker。
- 日志 JSON：`timestamp, level, traceId, workspaceId, jobId, message`。
- 指标：`weaveora_job_wait_seconds`、`weaveora_job_runtime_seconds`、`weaveora_llm_latency_seconds`、`weaveora_job_success_ratio`、`weaveora_nsfw_hits_total`、`weaveora_credits_reserved`。
- 告警：队列堆积 > 50、worker 心跳消失、失败率 > 10%、磁盘 > 80%、LLM 5xx。

---

## 25. 仓库与目录

建议 monorepo：

```
weaveora/
  docs/Weaveora.md
  packages/schemas/
  api/
    pom.xml
    src/main/java/studio/weaveora/
    src/main/resources/db/migration/
    src/main/resources/prompts/
  web/
  worker/
  app/
  deploy/
    compose/
    k8s/
    workflows/
  Makefile
```

Maven：`weaveora-parent` BOM。包名 `studio.weaveora.<module>`。

---

## 26. 编码规范

- Java：Google Style，**DTO 用 record**，**JPA 实体用 class**，constructor injection，禁止字段注入，禁止 `@Autowired` 字段。
- Repository 只继承 `JpaRepository` / `JpaSpecificationExecutor`，命名跟随 MirrorTalk 习惯（派生方法 + `@Query` JPQL）。
- 禁止 MyBatis、禁止 `EntityManager.createNativeQuery` 作为默认查询方式（报表除外）。
- 禁止 `System.out`；用 slf4j。
- 事务：写操作 `@Transactional`，查询只读。
- Vue：composition API，组件 PascalCase，禁止 `any`。
- Python：ruff + pyright。
- Commit：Conventional Commits（`feat(director): …`）。
- 每个模块 README 写清如何本地起。
- OpenAPI：springdoc-openapi，`/v3/api-docs`。

---

## 27. 测试

- API：Testcontainers（PG / Redis）测额度预扣与状态机；本机无 Docker 时用本机 PG / 单独 Redis，配 local profile。
- Director 校验器：纯单测（时长求和、JSON schema、负面词合并）。
- Worker：用假 ComfyUI / stub。
- Web：导演台确认按钮在未 approve 时禁用；分镜拖拽测时间码。
- 契约：`edit_list.json` schema 快照测试。
- Modulith：`ApplicationModules` 验证循环依赖。
- 不要在 CI 真打 GPU。
- 不要用 H2 跑集成测试。

验收剧本见第 31 章，实现模型必须写成自动化或 `docs/QA.md`。

---

## 28. 实施里程碑（v1.3 按单人实施重排，仍按周交付）

| 周 | 交付 | 完成定义 |
| --- | --- | --- |
| W1 | 仓库、Flyway V1、JPA 实体、用户 / 工作区 / 项目 CRUD、JWT 登录（验证码 Redis）、Vue 壳、暗色 token | 能登录并建空项目 |
| W2 | Director：LLM JSON、revision 版本、校验器、导演台 UI | 输入一句话得到可编辑提示词 / 剧本 |
| W3 | Job 状态机、额度（simplified / wallet）、Redis Streams、stub worker、进度 WS | 点确认后有假图回来 |
| W4 | 真 ComfyUI txt2img（GPU 机到位前保持 stub）、资产库、画廊 | 真出图或 GPU 机就绪后立即真出图 |
| W5 | 分镜墙、逐镜确认、still pass | 视频项目可按镜出关键帧 |
| W6 | 时间线粗预览、edit_list 导出、README | 能把媒体送进剪映手工对齐 |
| W7 | 剪映适配器、img2vid 可选、安全扫描 | 草稿包可导入或明确降级 |
| W8 | 管理后台、额度、观测、打磨 | 可给内测用户 |

GPU 机未到位时，W4 用 stub 保持接口；GPU 机到位后切 `WEAVEORA_WORKER_MODE=comfy`，MirrorTalk 的 Replicate 通道不受影响。  
**不要从 Flutter 或管理后台开干。**

### 28.1 实现模型开工顺序（第一周文件级）

1. `api` 骨架 + Modulith 包 + Flyway V1 + JPA 实体  
2. identity：注册登录 JWT，验证码 Redis  
3. project CRUD（全部带 `workspace_id`）  
4. `web` 登录 + 项目列表 + 新建  
5. dev 可起（本机 PG / Redis，或 Docker Compose postgres+redis+api+web+worker-stub）  
6. 再进入 director  

禁止第一周做：微服务拆分、Flutter、Seata、真实 ComfyUI、剪映私有格式逆向、H2、`ddl-auto=update`、内存验证码。

---

## 29. 风险

| 风险 | 缓解 |
| --- | --- |
| 剪映草稿格式非公开且常变 | 自有 `edit_list.json` 为源，适配器可弃 |
| GPU 成本 | 强制 still → confirm → motion；额度预扣 |
| LLM 不稳定 JSON | schema + 重试 + 校验器 |
| 角色不一致性 | IP-Adapter / 参考帧 / 种子锁，v1 再做 |
| NSFW 与版权 | 双层过滤 + 用户协议 |
| 微服务过度拆分 | MVP 单体（与 MirrorTalk 一致） |
| Java 团队不会 ComfyUI | Worker 独立仓，用 HTTP 契约隔离 |
| 把 MirrorTalk 同步 AI 习惯带进 GPU | 硬性指令 16：Job 必须进 Redis Streams |
| GPU 机未到位 | W4 保持 stub；Worker 契约不变，`WEAVEORA_WORKER_MODE` 切换 |
| 本机无 Docker | 用本机 PG 18 + 单独 Redis 跑 local profile；Compose 可选 |
| Hibernate 改表毁掉状态机 | Flyway + `ddl-auto=validate` |

---

## 30. 已锁定决策

2026-09-02 你确认：第 1–3、6–11 条同意；第 4、5 条按 MirrorTalk 实测裁定。2026-09-04 v1.3 裁定修订 #3 #4 并新增 #13–#19。实现模型按本表开工，不得再问。

| # | 决策 | 锁定值 | 来源 |
| --- | --- | --- | --- |
| 1 | 名称 | **Weaveora / 织影** | 同意 |
| 2 | 前端 | **Vue 3 + Naive/Arco 做 MVP**；Flutter 只做二期审片 | 同意 |
| 3 | 后端 | **Java 21 + Spring Boot 3.4.5 + Spring Modulith 1.3.x 单体**；MVP **不引 Spring Cloud / Nacos / Gateway / Sentinel**（拆服务后才引入）；Worker **Python FastAPI + ComfyUI**（自建，GPU 机后续） | v1.3 裁定 |
| 4 | 队列 | **Redis Streams**（消费组 + 独立死信流），Redis 7 内建 | v1.3 裁定替代 RabbitMQ（MirrorTalk 仍无 MQ，GPU Job 不能同步 block） |
| 5 | ORM | **Spring Data JPA + Hibernate 6** | MirrorTalk 实测即此。**不跟** `ddl-auto: update`，配 **Flyway + validate** |
| 6 | 生成引擎 | 自建 ComfyUI 为主（可灵 / 即梦为 v1 适配器）；**MirrorTalk 现有 Replicate 出图通道保持不变** | v1.3 裁定补充 |
| 7 | 剪映 | 导出草稿包，不做未授权客户端注入 | 同意 |
| 8 | 视频 | **强制两段式**：先关键帧，确认后再运动 | 同意 |
| 9 | 登录 | MVP 邮箱 + 密码；手机号可同期；微信后期 | 同意 |
| 10 | 风格 | 默认关闭「真人模特」类 | 同意 |
| 11 | 进度 | **WebSocket**（不改 SSE） | 同意 |
| 12 | 用户中心 | **不可直接复用**。重写 Auth + JWT，验证码/限流 Redis，用户模型按第 14 章 | MirrorTalk 实测 |
| 13 | 存储 | **dev：本地目录适配器**；生产：**阿里云 OSS**。不引 MinIO / AWS SDK v2 | v1.3 裁定 |
| 14 | 额度 | **wallet 为默认**，MVP 内测可切 `billing.mode=simplified`（配额常量、免费、无 ledger） | v1.3 裁定 |
| 15 | 配置 | **Spring profile + 环境变量**（镜像 MirrorTalk 生产方式）；拆服务后才用 Nacos | v1.3 裁定 |
| 16 | 网关 | **MVP 同进程 Security filter**；拆服务后才是 Spring Cloud Gateway | v1.3 裁定 |
| 17 | 限流 | MVP：Redis 计数 + 自定义注解（登录 5/s/IP 等）；Sentinel 拆服务后再上 | v1.3 裁定 |
| 18 | 版本 | **Java 21 + Boot 3.4.5 + Modulith 1.3.x + Flyway 10.x**（Boot 不再 3.3，对齐 MirrorTalk 3.4.x 实测） | v1.3 裁定 |
| 19 | 里程碑 | 按 **§28 v1.3 单人版** W1-W8 执行；GPU 机未到位时 W4 用 stub | v1.3 裁定 |

补充裁定（随 4、5 条一起锁死；v1.3 更新）：

- **Redis 7 第一天就上**（验证码、限流、锁、WS、Job 队列 Streams）。
- **开发 / CI 用 PostgreSQL，不用 H2。**
- **不引入 MyBatis-Plus。**
- **不把 GenerationJob 做成 `@Scheduled` 或同步 WebClient。**
- **不引入 RabbitMQ / Kafka / Nacos / Gateway / Sentinel / MinIO（拆服务或规模需要时再评估）。**

---

## 31. 演示与验收剧本（QA 必过）

1. 新用户注册 → 建「图片」项目 → 输入「被水淹的巴洛克图书馆，月光，不要人」→ 得到中英提示词 → 改一个词 → 确认 → 出至少 1 张图 → 下载。
2. 建「视频」项目 12 秒 16:9 → 输入「一只纸船穿越雨夜城市」→ 得到 ≥4 镜剧本，时长和为 12s ±0.5 → 改第 2 镜提示词 → 只确认第 2 镜仍不能生成全片 → 确认全片 → 先出 4 张关键帧 → 导出 zip 内有 `edit_list.json` 与 README。
3. 未确认时点生成，API 返回 `REVISION_NOT_APPROVED`。
4. 额度不足时预扣失败，Job 不创建。
5. 取消 queued 任务，状态为 cancelled，额度释放。
6. 用户 A 不能读用户 B 工作区的项目。
7. LLM 返回非 JSON，前端看到可读错误，不崩页。
8. 重启 API 后，未过期验证码仍有效（证明走 Redis 不是内存 Map）。
9. 停掉 Worker，创建 job 后状态保持 queued，Worker 恢复后继续，不丢任务（证明走 Redis Streams 消费组，不是内存）。

---

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

---

*结束。实现时以本文 v1.3 为唯一产品 / 架构真源。第 30 章已锁定（含 v1.3 裁定 #3 #4 #13–#19），可以开工。*
