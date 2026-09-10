# Weaveora QA（验收自动化 · §31 → W7 质量验收基线）

引擎说明：默认 stub（无 GPU/云也可跑流程）；真引擎（本地 Comfy 或云 worker）时用 `--engine cloud`
（依赖常驻 worker 完成生成）。自动化入口 `worker/qa_acceptance.py`，本地/生产同源跑。

## 一键验收
```bash
# 本地（需先起 api；stub 引擎自动拉起本地 stub worker）
python worker/qa_acceptance.py --base http://localhost:8080 --engine stub
# 生产（依赖线上 cloud/comfy worker 常驻，勿用 stub 以免抢任务）
python worker/qa_acceptance.py --base https://sysou.com/weaveora --engine cloud
```

## §31 → 用例映射
| QA | 自动化 | 说明 |
|---|---|---|
| 1 注册→图片→方案→改词→确认→出图→下载 | ✅ S1 | generate 正向词长度、approve、1 张任务成功、资产 png、下载字节 |
| 2 视频 12s→≥2 镜→改第2镜→单镜确认不能生成全片→整版→关键帧→motion→成片 | ✅ S3（motion 待真 Wan） | ≥2 镜、和=12±0.5、单镜≤10s；PATCH；整版 approve；导出 edit_list 包 |
| 3 一致性（参考图 4 张一致） | ⏳ GPU/带参考云档 就绪后 | 需本地 Comfy IP-Adapter（§30 #23） |
| 4 未确认点生成 → REVISION_NOT_APPROVED | ✅ S3 | 409 + code |
| 5 额度不足不创建 | ⏳ wallet 模式开启后 | 当前 simplified |
| 6 取消 queued → cancelled 释放 | ✅ 手动/后续 | cancel API 已实现 |
| 7 A 不能读 B 工作区 | ✅ 手测 | WorkspaceGuard 403 |
| 8 LLM 非 JSON → 可读错误 | ✅ | DIRECTOR_PARSE_FAILED 422 |
| 9 重启后验证码仍在（Redis） | ⏳ 手机验证码开通后 | 当前邮箱密码 |
| 10 停 worker 任务保持 queued，恢复后继续 | ✅ 手测 | 状态机 + claim |

## 安全主体分档（§11.4）
- MVP 放行：产品/物体/场景/风格、虚构人物/IP/动漫/吉祥物
- **可识别真人**：generate 前命中真人分档词（真人/真实人物/明星/本人/自拍/肖像/网红/real person/celebrity…）→ 422 `BRIEF_BLOCKED`（v1.0 解锁需肖像授权+AI 标识+深度合成合规）
- 可配：`weaveora.safety.real-person-words`（逗号分隔，缺省用内置词表）

## 引擎档位说明（W7 边界）
- 云（Replicate SDXL）：图片仍走云；clip(motion) 未接线（`CLOUD_MOTION_UNSUPPORTED`）
- motion 成片需本地 Wan（GPU）或云视频 API（高阶档，后置）；edit_list 导出以关键帧兜底
- 一致性验收（QA-3）待 GPU/带参考能力的云档

## 运维备忘：长视频（分段导演）经 nginx 的同步等待超时
- 症状：>30s 视频（触发分段导演）点「导演」约 60s 后返回 **404**（nginx 页，空 JSON）。
- 根因：`nginx proxy_read_timeout` 默认 60s；分段导演在 Spring 内串行多次 LLM 调用（每段最长 ~60s），
  整体可达数分钟，超过代理读超时即中断；`/usr/share/nginx/html/50x.html` 缺失时显示为 404。
- 修复：`location ^~ /weaveora/api/`（及 `/weaveora/internal/`）设
  `proxy_read_timeout 1800s; proxy_send_timeout 1800s;`（5 分钟上限 × 多段 × 重试余量）。
- 验证：31s→200（~95s）、60s→200（~156s）走公共 https 通过。
- 演进：若走向 5 分钟长片批量并发，应把 director/generate 改异步任务 + 进度（现状同步等待）。

## 运维备忘：云图片任务 `[Errno 101] Network is unreachable`（2026-09-10）
- 症状：女儿国 v7 第 1 镜重跑 still 任务在 `cloud_submit` 20% 失败，`error_code=CLOUD_ERROR`，`error_message=<urlopen error [Errno 101] Network is unreachable>`；同一任务参考图上传（api.replicate.com/files）已成功。
- 诊断：VPS 无 IPv6 默认路由（`curl -6` 直接 000），api.replicate.com（Cloudflare）偶发返回 AAAA；python3.6 urllib 命中 IPv6 → Errno 101。VPS 侧 `curl -4`/默认均可达（401=缺 token），属**瞬断**而非封禁。
- 修复（worker）：`stub_worker.py` 进程级 `socket.getaddrinfo` 强制 IPv4（失败回退默认）；`cloud_client.py` 新增 `_open_retry()`，对创建预测/轮询/下载做网络瞬断指数重试（HTTPError 不重试，避免误重试 4xx）。
- 验证：worker 进程内 `socket.getaddrinfo('api.replicate.com',443)` 仅返回 `AF_INET`。
- 旁证：该失败任务 payload 已是 `revision_no=7 + prompt_md5 + v7 正词`，确认版锚定修复生效（不再是旧版取词问题）。
- 口径澄清（2026-09-10 用户确认）：§11.6 的 SD/p-video 固定模型是** agent 调试用**，只在 `WEAVEORA_REPLICATE_TEST_MODELS=1` 时生效；**生产以用户引擎配置为准**。用户的 `black-forest-labs/flux-2-pro` 属正常用户配置，无需更改。

## 参考图绑定与重跑取图（2026-09-10）
- 症状：v8 重跑 S1，任务 payload 的 `referenceAssetIds` 仍是旧图（23:17），生成形象不对；英文正词本身正确。
- 根因：① 参考图选择只在新建 Brief 时随 constraints 落库，改方案时未持久化（`briefs.constraints={}`）；② 重试/重跑重锚只重建 prompt，未刷新 `referenceKeys/referenceAssetIds`；③ 无“主体↔参考图”绑定。
- 修复：`plan.referenceAssets=[{assetId,subject}]` 随方案保存；JobService 逐镜按文案命中主体自动绑定并追加“形象以参考图为准”锚定词；重锚时重新解析 refs；前端参考图面板可标注主体、生成前自动保存草稿；任务「只看最近一轮」按 镜号+类型+帧号 取最新（不分状态）。
- 复现验证：v8 勾选唐僧图 → 标注「唐僧」→ 保存/生成 → 新任务 payload 的 referenceAssetIds 应为新图且正词含 reference image 锚定句，`prompt_md5` 随之变化。

## 多主体参考图串脸（2026-09-10）
- 症状：同时勾选「女王」「唐僧」两张参考图重跑 S1，结果两个人物变成同一张脸（全女王或全唐僧）。
- 根因：① worker `replicate_image` 只用 `referenceKeys[0]`，并以单张图作 `image`（img2img/编辑语义）→ 整图身份被这一张带偏；② 方案内主体↔图映射顺序被 DB 查询顺序打乱，且 payload 未下发 `referenceSubjects`；③ 提示词只有一句笼统 “must follow the provided reference image”，未说明“第几张图=谁”。
- 修复：JobService 按标注顺序重建 ids/keys/subjects（新增 `referenceSubjects`/`primarySubject`），anchor 改为显式图序映射 + “各角色各随其图、禁止换脸/混脸”，多主体自动追加防串脸负词；worker 多图模型（flux/kontext/nano-banana）传全部 `input_images` 并把图序映射写进 prompt，单图/img2img 模型只用主主体那张（GPU IP-Adapter 同规则）。
- 已知限制：单图 img2img 类模型无法真正按角色分别绑脸；同框多主体要稳定需用多参考模型或拆镜/分帧，或后续做 IP-Adapter 分区遮罩（GPU）。

## P5 GPU 分区遮罩 IP-Adapter（代码就绪，待 GPU 唤醒验证）
- 目的：同框多角色（女王+唐僧）按区域分别注入参考图，消除串脸。
- 数据：`plan.referenceAssets=[{assetId, subject, region:{x,y,w,h}(0–1)}]`；前端参考图面板每个选中项可填「区域%」（x/y/w/h，0–100，填全且合法才生效）。job payload 下发 `referenceSubjects` + `referenceRegions`（与 keys 一一对应）+ `primarySubject`。
- comfy 图：`IPAdapterAdvanced(attn_mask)` 优先、否则 `IPAdapterMS(mask)`；遮罩由 worker 生成的灰度 PNG（白=区域）经 LoadImage→(MaskBlur 可选)→attn_mask 注入；无遮罩能力或未标注区域时只用主主体一张（避免串脸）。
- 云侧：无遮罩能力，改为在 prompt 追加 `Spatial layout: 女王 -> upper-left of frame; …` 方位描述 + 图序映射 + 防串脸负词。
- 验证（GPU 唤醒后）：① 选两张参考图各填区域（如女王 x0 y0 w50 h100；唐僧 x50 y0 w50 h100）；② 确认方案后生成关键帧；③ 看 worker 日志 `[comfy] refs=2 regions=2 primary=…` 与节点类型（IPAdapterAdvanced/MS）；④ 出图两角色脸不互相污染。
- 已知：平台自动睡眠导致当前未唤醒（用户后续处理）；本特性不影响云通道。

## motion 报“第N镜尚无关键帧”但历史版本有关键帧（2026-09-10）
- 根因：`shot_drafts` 每次 patch/确认都会 delete+recreate（**shot 行 id 变化**），而 still 资产永久挂在生成时的 shot id 上 → 当前确认版的同镜号新 id 查不到旧关键帧（实例：女儿国 v13 的第2/3镜仍无 still，v1 的第2/3镜关键帧资产还在）。
- 修复：JobService clip 分支在“当前 shot 无 still”时，按**同项目 + 同镜号**回溯所有历史 revision 的 shot 行，取最新 still 作首帧；payload 标 `keyframeHistorical=true` / `keyframeHistoricalRevisionNo=vN`，任务行悬停可看到“沿用历史版本第N镜关键帧（vX）”。
- 验证：女儿国（确认版 v13）点 motion → 不再报错，新 clip 任务 payload 的 keyframeKey 指向该镜历史关键帧，且带 keyframeHistorical 标记。

## P6 assets.shot_no 冗余列（治 shot 行 id 漂移的根因，2026-09-10）
- 根因：`shot_drafts` 每次 patch/approve delete+recreate（shot 行 id 变化）→ 资产按 shot_id 查不到（motion 误报无关键帧、导出/时间线丢素材）。
- 迁移 `V9__asset_shot_no.sql`：assets 增 `shot_no` + 索引 `(project_id, shot_no, kind)` + 从 shot_drafts 回填（已执行，女儿国 still 回填为 1/2/3）。
- 写入：`AssetService.createOutput` 增 `shotNo` 参数，`JobService.complete` 落库时写（来自 job.shotId → shotNo）。
- 读取（优先 shot_no，退 shot_id）：motion 关键帧、ExportService 成片包、ConcatService 渲染、前端资产库「第N镜」与时间线「素材已就绪」。
- 效果：换版/重确认后，同镜号素材自动被找到；不再依赖“历史版本回溯”（该兜底保留为最后一级）。
