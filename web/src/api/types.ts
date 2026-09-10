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

/** 导演方案（§10.2，前后端共享 packages/schemas/director.schema.json；key 为 snake_case） */
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
  narration?: string
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
  audio: { music_mood: string; sfx: string[]; vo: string }
  edit_plan: { fps: number; transition_default: string; subtitle: boolean }
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
  kind: string
  mime: string
  width: number | null
  height: number | null
  createdAt: string
}

/** 任务（JobView） */
export interface JobRecord {
  id: string
  projectId: string
  revisionId: string | null
  shotId: string | null
  kind: 'still' | 'clip'
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
    composition?: string
    tailKey?: string
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
}

/** 统一错误体（§17）：{ code, message, traceId } */
export interface ApiErrorBody {
  code: string
  message: string
  traceId?: string
}
