/** API 类型（与后端 record 一一对应，字段保持 camelCase；§17.2 / api module）。 */

/** POST /auth/register|login|refresh */
export interface TokenPair {
  accessToken: string
  refreshToken: string
}

/** GET /me */
export interface WorkspaceInfo {
  id: string
  role: string
}

export interface Me {
  id: string
  email: string | null
  displayName: string
  workspaces: WorkspaceInfo[]
}

/** 列表卡片（我的项目 / 集市 / 待审） */
export interface ProjectCard {
  id: string
  title: string
  mode: ProjectMode
  aspectRatio: string
  durationSec: number | null
  status: string
  shareStatus: string | null
  ownerName: string
  createdAt: string
  updatedAt: string
  likeCount: number
  favoriteCount: number
  liked: boolean
  favorited: boolean
}

/** 分页卡片列表 */
export interface ProjectPage {
  items: ProjectCard[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

/** 项目模式（§7.1） */
export type ProjectMode = 'image' | 'video' | 'mixed'

/** 风格模板（StyleTemplateController） */
export interface StyleTemplate {
  id: string
  slug: string
  name: string
}

/** 项目（ProjectResponse） */
export interface Project {
  id: string
  workspaceId: string
  title: string
  mode: ProjectMode
  aspectRatio: string
  durationSec: number | null
  shotDurationSec: number | null
  status: string
  styleTemplateId: string | null
  createdAt: string
}

/** Brief（BriefResponse） */
export interface Brief {
  id: string
  projectId: string
  rawText: string
  mode: string
  constraints: Record<string, unknown>
  createdAt: string
}

/** 镜内一段语音（P8）：旁白或角色台词 */
export interface NarrationLine {
  /** 该段在**镜内**的起始秒（相对镜头起点） */
  at_sec: number
  /** 该段在镜内的结束秒（可选）；设了就按这个窗口裁切配音，留空用配音自然长度 */
  end_sec?: number | null
  text: string
  /** 缺省：有 subject 则为 dialogue，否则 narration */
  kind?: 'narration' | 'dialogue' | null
  /** 说话人（角色/主体），与 audio.voiceBindings[].subject 关联 */
  subject?: string | null
  /** 本段音色覆盖（优先于 voiceBindings 与 audio.voice） */
  voice?: string | null
  speed?: number | null
  /**
   * P10：该段位置/结束点被**用户手动**改过。
   * 自动铺排（避让、按实际时长对齐）只动 `manual !== true` 的段，避免把用户拖好的位置改掉。
   */
  manual?: boolean | null
}

/** 角色 → 音色绑定（P8） */
export interface VoiceBinding {
  subject: string
  voice: string
  speed?: number | null
}

/** 配乐段落（P8）：可多段，各自控制起止/强弱/淡入淡出/循环/duck */
export interface MusicCue {
  id?: string
  /** 全片时间轴上的起始秒 */
  start_sec: number
  /** 全片时间轴上的结束秒（须 > start_sec） */
  end_sec: number
  /** 缺省用 audio.music_mood */
  mood?: string | null
  /** 音量 dB，0=原始；默认 -10.5（≈线性 0.30，同 P7）；“一半”约 -16.5 */
  gain_db?: number | null
  fade_in_sec?: number | null
  fade_out_sec?: number | null
  /** 生成曲短于区间时循环填充（默认 true） */
  loop?: boolean | null
  /** 是否被旁白侧链压低（默认 true） */
  duck?: boolean | null
}

/** P9 克隆音色（录音/上传样本 → 处理 → 可复用） */
export interface VoicePreset {
  id: string
  name: string
  /** 处理后的参考音资产 id（24kHz 单声道） */
  assetId: string
  /** 原声资产 id（用于 A/B 对比与“重录时清理旧文件”） */
  rawAssetId?: string | null
  /** 样本说了什么（whisper 转写，可手改）——传给 CosyVoice 的 prompt_text */
  promptText?: string | null
  durationSec?: number | null
  processed?: boolean | null
}

/** BasicPlan 共用音频配置（P8 扩展，字段均向后兼容） */
export interface PlanAudio {
  /** 整片默认配乐情绪（无 music 段时铺满全片） */
  music_mood: string
  /** 默认配音音色：内置名 / 参考音频路径 / clone:<id> */
  voice?: string
  /** P8 角色→音色绑定 */
  voiceBindings?: VoiceBinding[] | null
  /** P9 克隆音色库 */
  voicePresets?: VoicePreset[] | null
  /** P8 配乐段落表 */
  music?: MusicCue[] | null
  sfx: string[]
  vo: string
}

/** 导演方案（§10.2，前后端共享 packages/schemas/director.schema.json；key 为 snake_case） */
/** P13 剧情主体（参考图升级：勾选=参与锚定；定妆图=一致性锚定图） */
export interface PlanSubjectRef {
  assetId: string
  /** false = 不作为参考（替代原来的“删除才取消”） */
  checked?: boolean
  region?: { x: number; y: number; w: number; h: number } | null
}

export interface PlanSubject {
  name: string
  kind?: 'person' | 'vehicle' | 'object' | 'scene'
  aliases?: string[]
  enabled?: boolean
  locked?: boolean
  refs?: PlanSubjectRef[]
  /**
   * P5：**方案级默认位置**（「位置总控」→「方案默认（全片）」写这里）。
   *
   * 为什么单独按主体存、而不是挂在 `refs[].region` 上：主体可能只有定妆照、没有任何素材图
   * （实测本项目的 4 个主体 refs 全空）——那时方案级位置就**无处可存**了。
   * 优先级：帧级 `keyframes[].layout` &gt; 镜级 `shots[].layout` &gt; 本字段 &gt; `refs[].region` &gt; 点选坐标。
   */
  region?: { x: number; y: number; w: number; h: number } | null
  /** 定妆图（由素材图生成，优先用于分镜锚定） */
  portraitAssetId?: string
  portraitVersion?: number
  /**
   * ★ P14 主体设定（2026-09-16 用户要求）—— 「人物档案」，同时喂给 LLM 与视觉模型。
   *
   * 为什么必须有：定妆照只约束「长相」，而 LLM 写提示词、视觉模型画人还需要知道
   * **性别/年龄段/体态** —— 实测出现过「宝玉被当女性」（模型对中文人名没有可靠的性别常识）。
   */
  gender?: 'male' | 'female' | 'other' | ''
  age?: string
  height?: string
  build?: string
  personality?: string
  appearance?: string
}

export interface DirectorShot {
  shot_no: number
  duration_sec: number
  shot_size?: string | null
  camera_move?: string | null
  action?: string | null
  positive_prompt: string
  negative_prompt: string
  seed_lock: boolean
  ref_shot_no?: number | null
  /** 兼容：单段旁白（= narrations[0] 的简写） */
  narration?: string
  /** P8 镜内多段语音（存在时优先于 narration） */
  narrations?: NarrationLine[] | null
  /**
   * P13：对口型时「谁在哪张脸」——用户在看图上**点选**的结果。
   *
   * `{主体名: {x, y}}`，坐标归一化 0–1（相对画面宽高）。
   * 为什么必须有：LatentSync 每帧只能驱动一张脸，而原逻辑取「面积最大的脸」——
   * 多人同框时两张脸大小中途互换就会突然换人（画面坏掉）；而按定妆照做人脸识别
   * 在 480p/AI 古风这类风格化素材上区分度会崩（实测同一人只有 0.2 上下、且互相混淆），
   * 用户点一下是最可靠的信号，且不受画风影响。
   */
  lipsync_targets?: Record<string, { x: number; y: number }> | null
  /**
   * P5：**逐镜画面位置**（比方案级 region 优先）——UI「画面位置」编辑器写回。
   *
   * `[{subject, x, y, w, h}]`，归一化 0–1（相对画面宽高）；w/h 同时表达**远近与大小**
   * （框越大 = 离镜头越近）。与 `referenceSubjects` 同名即可自动关联；缺省时后端依次回退到
   * 方案级 `referenceAssets[].region` 与 `lipsync_targets`。
   */
  layout?: Array<{ subject: string; x: number; y: number; w: number; h: number }> | null
  /**
   * P5：**本镜出镜主体**（显式声明，覆盖后端按文本自动匹配）。
   *
   * - 不设（undefined）= 自动：后端按镜文本（action/zh/positive_prompt）匹配主体名；
   * - 非空数组 = 本镜就这几个主体（按这个名字顺序注入参考图）；
   * - **空数组 = 明确的空镜**（不注入任何人物参考图，避免把不相干的角色塞进环境镜）。
   *
   * 为什么需要它（用户实测）：镜文本没点到任何主体时，后端会「回退为全部主体」——
   * 多角色时容易串脸；某些帧甚至看起来没拿参考图。所以要在卡上勾选并回传。
   */
  cast?: string[] | null
  /**
   * P13：对口型的**底片**（用哪份画面驱动嘴型）——`'clip'` = 该镜最新 motion 片段，
   * `'still'` = 关键帧静帧；不设 = 自动（有 motion 用 motion，除非该镜是「惊恐/喊叫」
   * 这类底片里嘴本来就大张的镜头 → 自动改用静帧）。
   *
   * 为什么需要人工选：LatentSync 是「先把嘴合上、再按配音重开」的重绘模型——
   * 底片里嘴已经在动/大张时，嘴部掩码区形变最大（实测会把画面搞坏）；
   * 而静帧只有一张干净的脸，适合做说话镜的底片。
   */
  lipsync_source?: 'clip' | 'still' | null
  /** 对口型：即使底片体检（脸太小/嘴大张）不过也强制跑（默认 false，不推荐）。 */
  lipsync_force?: boolean | null
  /**
   * P10：该镜允许配音时长超出镜头。
   * 默认（不设）= 音频与字幕会自然溢到下一镜，但界面会提醒“配音总长超出镜头”；
   * 置 true 表示用户已确认，不再提醒。
   */
  allowNarrationOverflow?: boolean | null
  /**
   * P13：镜内分段（跨模型时长上限时把一镜切多段生成，成片仍是一镜）。
   * duration_sec = 各段之和；同镜内段间必须 cut 拼接。
   */
  segments?: Array<{ index: number; start_sec: number; duration_sec: number }> | null
  /**
   * P13：单段素材**时长不足**时（配音比模型单次上限长），是否用「本地重定时拉伸」补齐。
   *
   * 这样**不增加云端调用次数**（按次计费的模型尤其重要）：1 次调用出模型上限长度，
   * 渲染时 setpts 拉长到镜头需要的时间；音频位置不动。
   * 若为 false/未设且 segments 只有一段 → 渲染保持原速（画面会比配音短）。
   */
  stretch?: boolean | null
  /** P13：作者设定的目标镜长（shot_fixed 模式下用它取 min(模型上限, 目标)） */
  target_sec?: number | null
  zh?: string
  en_synced?: boolean
  /** P2 运镜关键帧（穿越/从A到B看到C）：≥2 帧时生成按帧出图，motion 用首/尾帧 */
  keyframes?: Array<{
    label?: string
    t?: number | string
    shot_size?: string
    camera_move?: string
    composition?: string
    positive_prompt: string
    /**
     * P5：**帧级**主体位置（运镜镜头的每一帧可各摆各的，一帧里可以有一个或多个主体框）。
     *
     * 优先级最高（高于 `shots[].layout` 与方案默认）；生成时后端按 `keyframe_index` 取对应帧的
     * `layout` 写进该帧那次出图的正词与 `referenceRegions`。
     */
    layout?: Array<{ subject: string; x: number; y: number; w: number; h: number }> | null
    /**
     * ★ 2026-09-16 夜（用户要求）：**帧级剧情主体**（运镜关键帧的每一帧可以出镜不同的人）。
     *
     * 语义与 `shots[].cast` 一致：`undefined` = 继承镜级 cast（再退文本自动）；`[]` = 该帧是空镜；
     * 非空 = 该帧就这几个主体。为什么必须有：运镜的第 2/第 3 帧常常换了视角/换了主体，
     * 以前只有镜级 cast → 这些帧只能"继承 + 猜"，出图随意（用户实测反馈）。
     */
    cast?: string[] | null
  }> | null
}

export interface BasePlan {
  mode: 'image' | 'video'
  title: string
  logline: string
  /**
   * ★ P14 设定年代/世界观（2026-09-16 用户要求）：项目必须交代剧情发生的时间/年代。
   *
   * 为什么不写在 script.theme 里：theme 是「主题」（讲什么），era 是「年代」（穿什么、有什么），
   * 两者混在一起模型会把「主题词」当成年代约束。era 会被系统追加到每一镜的正词里（JobService.applySetting）。
   */
  setting?: { era?: string; notes?: string }
  /** P4 参考图与主体绑定（参考图面板标注后随方案保存；生成时按镜文案自动绑定） */
  referenceAssets?: Array<{
    assetId: string
    subject?: string
    /** 方案级默认区域（归一化 0–1；「位置预览」卡拖动/填写所得） */
    region?: { x: number; y: number; w: number; h: number } | null
  }>
  /** P13 剧情主体（定妆图/素材图 + 方案级默认区域） */
  subjects?: PlanSubject[]
  [k: string]: unknown
}

export interface ImagePlan extends BasePlan {
  prompt_zh: string
  positive_prompt: string
  negative_prompt: string
  camera: {
    focal_mm: number
    shot_size: string
    angle: string
    /** P2 机位原子字段（可选，导演输出后写入 prompt） */
    viewpoint?: string
    foreground?: string
    subject_axis?: string
    focus_subject?: string
    composition?: string
  }
  lighting: string
  palette: string[]
  params: { width: number; height: number; steps: number; cfg: number; sampler: string; seed: number | null }
}

export interface VideoPlan extends BasePlan {
  duration_sec: number
  aspect_ratio: string
  script: { theme: string; acts: Array<Record<string, unknown>> }
  shots: DirectorShot[]
  audio: PlanAudio
  edit_plan: {
    fps: number
    transition_default: string
    subtitle: boolean
    /** P13：视频模型「单次输出上限」秒（不同模型不同：i2v 常见 5s，部分 15s+） */
    video_model_max_sec?: number
    /** P13：镜头尾部呼吸余量（秒），默认 0.3 */
    tail_sec?: number
    /**
     * P13：配音超过模型单次上限时的策略（直接决定云端调用次数/成本）。
     * stretch=本地拉伸(默认,不多花钱) | overflow=配音溢出下一镜(不多花钱) | segment=切段(多消耗调用)
     */
    oversize_policy?: 'stretch' | 'overflow' | 'segment'
    /**
     * P13：镜头时长由谁决定。
     * shot_fixed（默认）= 镜长取 min(模型上限, 目标镜长)，配音顺排、允许溢出到下一镜（每镜 1 次调用）；
     * audio_first = 镜长跟着配音走（超上限时按 oversize_policy 处理）。
     */
    timing_mode?: 'shot_fixed' | 'audio_first'
  }
}

export type DirectorPlan = ImagePlan | VideoPlan

/** POST director/generate 响应 */
export interface GenerateResult {
  revisionId: string
  revisionNo: number
  source: 'llm' | 'stub'
  projectStatus: string
  plan: DirectorPlan
}

/** revision 列表项 */
export interface RevisionSummary {
  id: string
  revisionNo: number
  source: 'llm' | 'stub' | 'user'
  title: string | null
  logline: string | null
  mode: string
  approved: boolean
  createdAt: string
}

/** 镜头落库行 */
export interface ShotRecord {
  id: string
  shotNo: number
  durationSec: number
  shotSize: string | null
  cameraMove: string | null
  action: string | null
  positivePrompt: string
  negativePrompt: string
  seedLock: boolean
  refShotNo: number | null
  status: string
}

/** revision 详情 */
export interface RevisionDetail {
  id: string
  briefId: string
  revisionNo: number
  source: 'llm' | 'stub' | 'user'
  approved: boolean
  plan: DirectorPlan
  shots: ShotRecord[]
  createdAt: string
}

/** 资产（AssetResponse） */
export interface AssetRef {
  id: string
  projectId: string
  jobId: string | null
  shotId: string | null
  /** P6 冗余镜号（shot_drafts 重建后仍能对应到镜） */
  shotNo?: number | null
  kind: string
  mime: string
  width: number | null
  height: number | null
  /** 产物快照（定妆图用它记录 subject / portrait_version） */
  promptSnapshot?: Record<string, unknown> | null
  /** P13：该产物对应的剧情主体（定妆图按它归类） */
  subject?: string | null
  /** P13：定妆图版本号 */
  portraitVersion?: number | null
  /** P13：快照里的产物类别（portrait=定妆图；兼容历史 kind=still） */
  snapshotKind?: string | null
  /**
   * P13：该产物画面里是否检出了人脸（后端由 prompt_snapshot.faceDetected 派生）。
   * true=抽样帧**全部**检出（对口型要求每帧都要有人脸）；false=没全检出（跑不了）；
   * null/undefined=未知（历史产物）。具体比例看 faceFrames。
   */
  faceDetected?: boolean | null
  /** P13：检出人脸的抽样帧数，如 "6/6" "4/6" "0/6" */
  faceFrames?: string | null
  /**
   * ★ 2026-09-16 夜：worker 写在产物快照里的「因显存做的取舍」说明，如
   * 「显存不够 → 分辨率自动降到 704x384（时长与速度不变，只略糊）」。
   * 为什么要暴露到前端：motion 显存不够时 worker 会自动降分辨率（或万不得已降帧），
   * 这个决定必须让用户看得见，而不是藏在 VPS 日志里。
   */
  notes?: string | null
  /** P10：产物真实时长（毫秒）—— 配音靠它对齐字幕、判定超长 */
  durationMs?: number | null
  /** P10：配音在镜内的段号（null = 非配音产物） */
  lineIndex?: number | null
  createdAt: string
}

/** 任务（JobView） */
export interface JobRecord {
  id: string
  projectId: string
  revisionId: string | null
  shotId: string | null
  // 必须覆盖后端允许的全部 kind（JobService 白名单：still|clip|voice|bgm|portrait|lipsync）。
  // 漏项会让 TS 在模板里把 kind 收窄，出现「这个比较不可能成立」的假报错，
  // 也容易让新产物类型在各处分支里被漏掉（P13 实例：lipsync 卡片掉进 <img> 分支）。
  kind: 'still' | 'clip' | 'voice' | 'bgm' | 'portrait' | 'lipsync'
  state: string
  progress: number
  stage: string | null
  cancelRequested: boolean
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  payload?: {
    shot_no?: number
    revisionId?: string
    revision_no?: number
    prompt_md5?: string
    positive_prompt?: string
    seed?: number
    keyframe_index?: number
    keyframe_count?: number
    frame_label?: string
    /** clip：运动帧数 */
    frames?: number
    /** voice：该镜内的第几段语音（0 基）—— 一镜多段配音靠它区分 */
    line_index?: number
    line_kind?: string
    at_sec?: number
    subject?: string
    text?: string
    /** bgm：情绪（同一情绪共用一个产物位） */
    mood?: string
    composition?: string
    tailKey?: string
    keyframeHistorical?: boolean
    keyframeHistoricalRevisionNo?: number
    preview?: boolean
  } | null
}

/** POST /projects 请求体 */
export interface CreateProjectInput {
  title: string
  mode?: ProjectMode
  aspectRatio?: string
  durationSec?: number | null
  shotDurationSec?: number | null
  styleTemplateId?: string | null
}

export type EngineKind = 'gpu' | 'cloud'

/** 生成引擎配置（GET/PUT /api/v1/me/engine-settings） */
export interface EngineSettings {
  imageEngine: EngineKind
  videoEngine: EngineKind
  imageCloudBaseUrl: string | null
  imageCloudAuthType: 'api_key' | 'basic'
  imageCloudModel: string | null
  imageCloudApiKeyMask: string
  imageCloudUsername: string | null
  imageCloudPasswordSet: boolean
  videoCloudModel: string | null
  videoCloudApiKeyMask: string
  gpuServerUrl: string | null
  gpuServerPort: number | null
  /** GPU 服务器最大支持分辨率：480p/720p/1080p/auto（motion 出片上限；机器能力，换卡就改） */
  gpuMaxResolution: string | null
  /** P12：模型调用参数说明（拉取缓存） */
  imageModelSchema: ModelSchema | null
  videoModelSchema: ModelSchema | null
  /** 用户全局参数（画质等） */
  imageParams: Record<string, unknown> | null
  videoParams: Record<string, unknown> | null
  /** P12：拉取参数说明的失败原因（有值时不要显示可能过期的说明） */
  imageModelSchemaError: string | null
  videoModelSchemaError: string | null
  /** P12：网关通道单次最多参考图张数（0/空=未知） */
  gatewayRefsMax: number | null
  /** P12：网关通道：已保存的示例请求（curl / JSON body），用于解析参数格式 */
  gatewaySample: string | null
  /** P12 模型库：已配置的模型条目 */
  imageModelPresets: ModelPreset[] | null
  videoModelPresets: ModelPreset[] | null
  /**
   * 服务地址（配音/配乐、对口型、整脸口型、文生图、转写、人脸），后端已填默认值。
   *
   * ★ GPU 公网 IP/端口会变：**只改「GPU 服务器地址 + 端口」这一处**，
   *   下面这些留空即自动跟随（后端推导成 `<gpu>/audio`、`<gpu>/talk`、`<gpu>:8001` …）。
   *   不要把 IP 写进 worker 脚本、部署脚本或代码里。
   */
  services?: ServiceEndpoints | null
}

/** 服务地址：配音/配乐、对口型、转写、人脸（见后端 V15__engine_services.sql） */
export interface ServiceEndpoints {
  /** 配音（CosyVoice 等 TTS 服务） */
  tts?: { url?: string | null } | null
  /** 配乐（engine=comfy 走 ComfyUI 里的 ACE-Step；http 走独立音乐服务） */
  music?: { engine?: string | null; url?: string | null; ckpt?: string | null } | null
  /** 对口型（LatentSync via ComfyUI）：comfyUrl + 本机工作流路径 */
  lipsync?: { comfyUrl?: string | null; workflow?: string | null; timeout?: number | null; fps?: number | null } | null
  /** 转写（语音转文字，通常与配音同一台机器） */
  transcribe?: { url?: string | null } | null
  /** 人脸（人脸预检/锁人；空 = 用 worker 本机 insightface，填了则调远端服务） */
  face?: { url?: string | null; latentsyncDir?: string | null } | null
  /**
   * 整脸口型（EchoMimicV3「jaw-lip」；喊叫/尖叫/吟唱镜用它替代 LatentSync）。
   *
   * 跑在 GPU 机的 `talk_server.py`（默认 :8094，网关路径 `/talk`）。**留空** = worker 读环境变量
   * `WEAVEORA_TALK_URL`；配了「GPU 服务器地址」时后端会自动把它推导成 `<gpu>/talk`。
   */
  talk?: { url?: string | null; enabled?: boolean | null; jawGain?: number | null } | null
  /**
   * 文生图（本机 ComfyUI 出图，Qwen-Image / FLUX）。
   *
   * `engine=comfy` 时 worker 用 `workflow`（文生图）/`editWorkflow`（参考图锚定，Qwen-Image-Edit）/
   * `img2imgWorkflow`（关键帧当底图）的 API 格式 JSON 直接 POST 给 ComfyUI；
   * 路径是**worker 机器上的绝对路径**（装在哪台机就填哪台机的）。
   * 留空 = worker 用自带默认（老 SDXL/IP-Adapter 路线）。
   * 优先级：有参考图且有 editWorkflow → Edit；否则 img2img；再否则 txt2img。
   */
  image?: {
    engine?: string | null
    comfyUrl?: string | null
    workflow?: string | null
    /** 参考图锚定工作流（Qwen-Image-Edit）；填了就优先走它 */
    editWorkflow?: string | null
    img2imgWorkflow?: string | null
    model?: string | null
    sampler?: string | null
    steps?: number | null
    /** true_cfg_scale（CFG）：直接决定提示词遵从度；0/留空 = 用工作流 JSON 自带值 */
    cfg?: number | null
    width?: number | null
    height?: number | null
    denoise?: number | null
  } | null
}

/** P12 模型库条目（一个已配置过的 baseUrl + 模型 + 参数 + 参数说明） */
export interface ModelPreset {
  baseUrl: string
  model: string
  params: Record<string, unknown> | null
  schema: ModelSchema | null
  schemaAt?: string
  schemaError?: string
  gatewayRefsMax?: number
  gatewaySample?: string
  updatedAt?: string
}

/** P12：云模型 input schema 归一化结果（后端 ModelSchemaService 产出） */
export interface ModelSchema {
  provider: string
  model: string
  version: string
  versionCreatedAt?: string
  fetchedAt?: string
  /** 精简调用说明：名字/类型/默认值/枚举/说明/是否可被用户改 */
  params: ModelSchemaParam[]
  /** 归一化参数映射（worker 按它填参）：refs/prompt/aspect/seed/width/height/negative/lastFrame… */
  mapping: Record<string, string | number | boolean>
  notes: string[]
  /** 网关通道（方舟等）探测结果：模型是否存在、模态、任务类型、提示 */
  gatewayProbe?: {
    ok?: boolean
    modelExists?: boolean
    takesImage?: boolean
    inputModalities?: string[]
    outputModalities?: string[]
    taskTypes?: string[]
    note?: string
    error?: string
    modelCount?: number
  } | null
}

export interface ModelSchemaParam {
  name: string
  type: string
  default?: unknown
  enum?: string[]
  max?: number
  maxItems?: number
  required?: boolean
  desc?: string
  group?: 'refs' | 'prompt' | 'quality' | 'control' | 'other'
  userEditable?: boolean
}

export interface EngineSettingsInput {
  imageEngine?: EngineKind
  videoEngine?: EngineKind
  imageCloudBaseUrl?: string | null
  imageCloudAuthType?: 'api_key' | 'basic'
  imageCloudApiKey?: string
  imageCloudUsername?: string | null
  imageCloudPassword?: string
  imageCloudModel?: string | null
  videoCloudApiKey?: string
  videoCloudModel?: string | null
  gpuServerUrl?: string | null
  gpuServerPort?: number | null
  gpuMaxResolution?: string | null
  /** P12：全局参数（画质等），键须在模型 schema 里存在 */
  imageParams?: Record<string, unknown> | null
  videoParams?: Record<string, unknown> | null
  gatewayRefsMax?: number | null
  gatewaySample?: string | null
  /** 服务地址（配音/配乐、对口型、转写、人脸）；null=不改 */
  services?: ServiceEndpoints | null
}

/** 统一错误体（§17）：{ code, message, traceId } */
export interface ApiErrorBody {
  code: string
  message: string
  traceId?: string
}

/* ==================================================================
   我的剧本（/api/v1/scripts）—— 字段与后端 record 一一对应
   ================================================================== */

/** 6 个可 AI 生成/更新的要素字段（标题与类型除外） */
export type ScriptFieldKey =
  | 'characters'
  | 'story'
  | 'conflict'
  | 'plotStructure'
  | 'language'
  | 'stageDirections'

/** 剧本详情（ScriptResponse） */
export interface Script {
  id: string
  workspaceId: string
  title: string
  genre: string
  characters: string
  story: string
  conflict: string
  plotStructure: string
  language: string
  stageDirections: string
  /** AI 持续维护的「精简的故事」（后续每一集生成的唯一连续记忆） */
  condensedStory: string
  /** 【B】各要素已持久化的分段写作提纲（key=ScriptFieldKey）；「AI 更新」可复用 */
  outlines?: Record<string, string[]> | null
  status: 'draft' | 'writing' | 'completed' | string
  shareStatus: string | null
  episodeCount: number
  charCount: number
  createdAt: string
  updatedAt: string
}

/** 剧本列表卡片（我的剧本 / 剧本精选 / 待审共用） */
export interface ScriptCard {
  id: string
  title: string
  genre: string
  status: string
  shareStatus: string | null
  ownerName: string
  episodeCount: number
  charCount: number
  excerpt: string
  createdAt: string
  updatedAt: string
  likeCount: number
  favoriteCount: number
  liked: boolean
  favorited: boolean
}

/** 剧本分页（默认 8/页） */
export interface ScriptPage {
  items: ScriptCard[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

/** 一集 */
export interface ScriptEpisode {
  id: string
  episodeNo: number
  title: string
  content: string
  summary: string
  aiPolished: boolean
  /** 【B】本集写作时用的节拍提纲（重开可见）；无则空数组 */
  outline?: string[] | null
  /** 这一集已经转出的项目（无则不返回）——行上显示「查看项目 V{n}」 */
  projectId?: string | null
  projectTitle?: string | null
  /** 该项目当前版本号（V{n}） */
  projectRevisionNo?: number | null
  createdAt: string
  updatedAt: string
}

/** AI 一致性检查给出的一条「历史章节需改动」建议（Q4：用户确认后才应用） */
export interface ScriptConflict {
  episodeNo: number | null
  title: string
  issue: string
  fix: string
}

/** 变更记录（含被同步改动的历史章节） */
export interface ScriptChange {
  id: string
  kind: string
  episodeNo: number | null
  changedEpisodes: Array<{ episodeNo: number | null; title: string; what: string }>
  note: string
  actor: 'user' | 'ai' | string
  createdAt: string
}

/** 保存一集的结果（含待确认的历史章节改动） */
export interface EpisodeSaveResult {
  episode: ScriptEpisode
  condensedStory?: string
  conflicts?: ScriptConflict[]
  changes?: ScriptChange[]
  completedBeats?: string[]
  source?: string
}

/** 单字段 AI 结果（Q3：前端做 diff，用户确认后再写入） */
export interface AiFieldResult {
  field: ScriptFieldKey | string
  value: string
  note: string
  changed: boolean
  source: string
  /** 【B】本次的分段写作提纲（每段一行）；空=未生成提纲 */
  outline?: string[] | null
}

/** 下一集草稿（未落库） */
export interface AiNextEpisodeResult {
  episodeNo: number
  title: string
  summary: string
  content: string
  source: string
  /** 需要告知用户的提示（如某段生成失败/未达目标长度）；无则 null */
  note?: string | null
  /** 【B】本集的节拍提纲（每段一行）；无则 null */
  outline?: string[] | null
}

/** 引擎在线状态（GET /api/v1/engine/status）：GPU 不在线时只给提示，不拦截生成 */
export interface EngineStatus {
  gpuOnline: boolean
  cloudOnline: boolean
  /** 可直接展示的中文提示；空串 = 都在线 */
  notice: string
}

/** 润色「我自己写的正文」结果（不落库：前端填进编辑器，用户确认再保存） */
export interface AiPolishResult {
  content: string
  /** 需要告知用户的提示（分段润色/自动精简/某段失败）；无则空串 */
  note: string
  source: string
  /** 润色前字数（便于展示「N → M 字」） */
  originalChars: number
}

/** 精简故事刷新 + 一致性检查结果 */
export interface AiCondensedResult {
  condensedStory: string
  conflicts: ScriptConflict[]
  completedBeats: string[]
  source: string
}

/** AI 引导 */
export interface AiGuideResult {
  stage: string
  missingBeats: string[]
  suggestions: string[]
  estimatedRemainingEpisodes: number
  source: string
}

/** 应用一致性改动结果 */
export interface ApplySyncResult {
  episodes: ScriptEpisode[]
  condensedStory?: string
  changes?: ScriptChange[]
  source?: string
}

/** 转成项目结果 */
export interface ConvertToProjectResult {
  projectId: string
  briefId: string
  revisionId: string | null
  shotCount: number
  projectTitle: string
  note?: string
  /** 本次生成的是项目里的第几版（V{n}） */
  revisionNo?: number | null
  /** true = 复用了已有项目出**新版本**（没有新建项目） */
  appended?: boolean
}

