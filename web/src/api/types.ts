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
  /** 定妆图（由素材图生成，优先用于分镜锚定） */
  portraitAssetId?: string
  portraitVersion?: number
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
  }> | null
}

export interface BasePlan {
  mode: 'image' | 'video'
  title: string
  logline: string
  /** P4 参考图与主体绑定（参考图面板标注后随方案保存；生成时按镜文案自动绑定） */
  referenceAssets?: Array<{ assetId: string; subject?: string }>
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
   * true=检出；false=抽样一帧都没检出（对口型跑不了）；null/undefined=未知（历史产物）。
   */
  faceDetected?: boolean | null
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
  /** P12：全局参数（画质等），键须在模型 schema 里存在 */
  imageParams?: Record<string, unknown> | null
  videoParams?: Record<string, unknown> | null
  gatewayRefsMax?: number | null
  gatewaySample?: string | null
}

/** 统一错误体（§17）：{ code, message, traceId } */
export interface ApiErrorBody {
  code: string
  message: string
  traceId?: string
}
