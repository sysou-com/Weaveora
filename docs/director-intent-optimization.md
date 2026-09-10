# 导演层“用户意图到不了模型”复盘与优化方案（女儿国项目）

> 日期：2026-09-10 ｜ 范围：文生图（分镜关键帧 still）意图传达链路
> 结论先行：**用户每次改镜的意图其实都正确写进了方案（v2–v7），但出图任务始终钉在最早确认的 v1 上重跑 —— 是“任务版本锚定”错位，不是模型听不懂人话。** 另有两层结构性缺口（改镜没有自然语言通道、单帧 schema 表达不了“穿越运镜”），一并给出优化方案。

---

## 1. 近几次操作记录复盘（生产库 weaveora，项目 女儿国）

项目：`0a000003-a086-182f-81a0-869bedd40005`（video / 16:9 / 15s，最新）
对照项目：`0a000003-a086-1bfe-81a0-8674c0a90002`（早一轮）

| 时间 | 事件 | 证据 |
| --- | --- | --- |
| 09-09 22:41 | v1 由 LLM 生成：S1=女王端坐惊抬头（大远景 dolly in），S2/S3=女王特写 | prompt_revisions rev1 schema_json |
| 09-09 23:17 | v1 确认；S1–S3 三个 still 任务全部成功（cloud 引擎，rev1 的 prompt） | generation_jobs 23:17:36 三行 + assets 23:18–23:19 |
| 09-09 23:17 / 00:19 | 用户上传参考图 ×2（第 2 张 = rev1 的 S1 出图原样回传，做脸的一致性锚定） | assets ref 两行（md5 与 rev1 S1 产物相同） |
| 09-10 00:05–01:01 | 用户连续 6 轮改镜：v2 缩时；**v3/v4 “镜头中唐僧是背影，女王是正面”；v5 “唐僧只看见背影”；v6（又改回正面）v7 最终“透过唐僧背影窥见女王美轮美奂的脸庞…女王面向唐僧”**；v7 于 01:01:02 确认 | revisions 2–7（source=user），schema_json/shot_drafts 文案逐版可查 |
| 09-10 00:06–01:01 | 期间 5 次重跑出图，**payload 全部仍是 v1 的 old prompt**（“女王…慢速推近她脸庞”），最后一次 01:01:20 的 rerun 在 v7 确认后 18 秒，依然用 v1 prompt | generation_jobs payload / revision_id 均指向 rev1（…790007）；日志 `job rerun …-> new … route=cloud` |

**镜像产物佐证**：最新一张 S1 still（01:01:45 落库）与 23:18 首张仍是同一套“女王端坐大远景”构图 → 用户看到“我反复说从背影穿过去看女王脸，出的还是老图” = 出图任务根本没吃新 prompt。

## 2. 根因（按因果链排）

1. **R1（主因）任务版本锚定错位**：`generation_jobs` 只在创建瞬间拷贝“当时已确认版本”的 shot prompt 进 payload（快照）。之后用户改镜 → 重新确认新版本，旧任务的**重试/重跑却仍复制旧 payload 与旧 revisionId**（JobService.retry 复用 `old.payload()`，且生产端存在对成功任务 rerun 的入口）。于是 v2–v7 的所有意图都停在纸面，喂给文生图引擎的永远是 v1 文本。
2. **R2（入口）改镜没有自然语言通道**：UI 里逐镜只能手工改 `action / positive_prompt` 英文字段（ShotCard 文本框），没有“导演按我的意见改这一镜”；“导演再给一版”只把**原始 Brief** 重新喂 LLM（`DirectorService.buildUserPrompt` 无任何历史/上一版/用户补充上下文），6 轮迭代的意图在重导时全部丢失，只能靠手写英文绕。
3. **R3（表达）单帧 schema 装不下“穿越运镜”**：用户要的“镜头从唐僧背影穿过→看见女王的脸”是一条相机路径（两个端点），而 still 只有 1 个关键帧：positive_prompt 顶多折中成“唐僧背对镜头、女王面向镜头”，engine 构图的几何关系（前景遮挡人物、机位在唐僧身后、视线轴、景别）没有结构化字段约束，文生图模型自由发挥；`camera_move` 字段只存在于分镜文本，甚至不进 still/clip 的 payload。加上第 2 张参考图就是 rev1 的“女王端坐”成品，IPAdapter/参考一致性还会把构图往回拉。
4. **R4（可观测）job 列表不显示生成所用版本/prompt**：任务行只有 kind/state，用户无法发现“这次跑的其实还是 v1”，于是把版本错位归结成“模型不懂我”，反复无效重试。

## 3. 本轮已落地修复（P0：让意图真正到引擎）

- **api `JobService.retry` 重新锚定**：重试时若项目“当前确认稿”已前进到新版本，则按确认稿对应镜头重建 payload（最新 positive/negative_prompt、duration、revisionId、shotId），再入队；找不到对应镜头/图片项目按新确认稿换词；clip 因依赖关键帧不自动改锚并告警；解析失败安全回退旧 payload。日志输出 `job retry re-anchored oldRevision→approvedRevision`。编译通过（`mvn -o compile`）。
- **web `ProjectDetailView`**：
  - 任务行新增版本胶囊：`vN`，非当前确认稿生成的旧任务显示红字 `vN·旧` 并悬停说明，版本错位一目了然；
  - 生成关键帧 / 运动任务统一以**当前确认稿**发起（不再依赖选中 tab），成功提示带版本号 `基于确认稿 vN`；
  - 重试成功提示说明“确认稿已更新时按当前确认稿 vN 重新取词”。type-check 与 `vite build` 均通过。

> 注：线上运行 jar（09-10 00:55 构建）与仓库 HEAD 存在未提交差异（生产有 succeeded 任务的 rerun 入口、user 来源版本另存逻辑等），部署时需先确认以仓库 main 为基线重打 api+web，勿直接 scp 旧产物。

## 4. 后续优化方案（P1→P3，建议排期）

**P1 给改镜一个自然语言入口（治 R2，投入 0.5–1d）**
1. 复用现成 `director_rewrite_shot.md`：加端点 `POST /director/shots/{shotId}/rewrite`（输入：自由文本意见），DirectorService 组装 = 当前确认稿全量 JSON + 目标镜 shot_no + 意见 + 约束 → LLM 只回该镜新 JSON → 校验后落为**新 revision（版本号+1）并自动 approve**？否——按 §0-3 确认闸门，落新版本但仍需人工“确认方案”后一键生成（P0 已保证从此确认稿生成）。前端 ShotCard 加“让导演改这一镜”文本区+按钮。
2. `generate` 增加可选 `notes`/`basedOnRevisionId`：重导时把“上一版 + 用户补充”作为上下文一起给 LLM，而不是只给原始 Brief（`DirectorService.buildUserPrompt` 扩展）。

**P2 表达穿越/运镜 = 关键帧拆分（治 R3 主）+ 构图词规范化（0.5–1.5d）——🟡 MVP 已实施并上线（2026-09-10）**
1. ✅ 分镜 schema 支持 `keyframes: [{label, t, shot_size, camera_move?, composition, positive_prompt}]`（2–4 帧）；导演 System Prompt 强制「穿越/从A到B看到C」类运镜输出起始帧+结束帧（带 composition 机位/遮挡描述）；校验器新增关键帧规则；**无 DB 迁移**（keyframes 存 `prompt_revisions.schema_json`）。
2. ✅ 生成路径：still 按帧一任务（payload 带 `keyframe_index/frame_label/composition`，同 seed、额度按帧计）；motion 取 `keyframe_index=0` 为首帧并额外传 `tailKey`（末帧）——引擎支持后即双关键帧引导。任务行显示「· 起始帧/结束帧」。
3. ✅ 前端：分镜卡展开可查看/编辑各关键帧 label/景别/prompt（列出 composition）；`normalizePlan` 修复为保留 `keyframes/narration/zh/en_synced` 扩展字段（原实现会在保存时剥离）；client planProblems 增加关键帧长度校验。
4. ✅ 图片导演机位原子字段：image schema `camera` 增 `viewpoint/foreground/subject_axis/focus_subject/composition`（含 enum 说明）；导演 System Prompt 强制填写并写进 positive_prompt；编辑器可查看/修改；`normalizePlan` 保留扩展字段。
5. ✅ 参考图-机位冲突提示：分镜/Brief 文本命中「背影/过肩/机位/穿过」且已选参考图时，参考图面板提示「参考图可能拉回构图，建议临时取消勾选」。
6. ✅ worker 消费 `tailKey`：`cloud_client.generate_motion_via_replicate` 在 `WEAVEORA_VIDEO_LAST_FRAME_PARAM` 配置时把末帧作为该参数上传；**p-video 无末帧参数，默认忽略并打日志**（未来 Wan 系/支持末帧的模型可直接开）。
7. ✅ 云 API 测试口径写入 `Weaveora.md` §11.6 + §30 #27：仅 replicate.com；出图固定 `stability-ai/stable-diffusion:ac732df8…`；视频 `prunaai/p-video` 且 draft=ON、≤720p（worker 默认值已按此实现，可用 env 覆盖）。

**P3 让版本与取词过程可见可审计（0.5d）——✅ 2026-09-10 已实施并上线**
1. `generation_jobs.payload` 在创建/重试重锚定时写入 `revision_no` 与 `prompt_md5`（对最终送引擎的正词取 MD5，含风格模板注入后文本）；`PlanReader.revisionNo()` 提供版本号查询。
2. 前端：任务行悬停显示「版本 vN · md5 · 提示词预览」（旧版标红）；资产库缩略图标注 `vN`，旧图不再被误当新效果。

## 5. 验证清单
1. 复现路径回归：确认 v7 后对旧 failed/succeeded 任务重试 → 新 job 的 payload 的 `positive_prompt` 与 v7 S1 一致（不再是“女王端坐”文案）。
2. UI：任务行出现 `v7` 徽标；旧 v1 任务标 `v1·旧`。
3. 文案/引擎层：用 v7 文案实跑 S1，肉眼核对是否出现“唐僧背影在画面前景（虚化遮挡）→ 女王正脸”的透视构图；不满足则进入 P2 的机位原子字段改造。

## 附：为什么“反复提示”看起来像没传到
用户 6 轮改的是**方案文件**（都成功了），而 5 次重跑的是**出图任务快照**（都在 v1）——两条记录流互不相关又都在同一页面上，前端未展示任务生成时的版本/prompt，用户无从分辨。这正是 §2 R1+R4 的组合，也是本轮修复要消灭的盲区。
