# P0-① 配音优先的时长校准（audio-first timing）

> 目标：消除「配音与画面脱节」。核心是把流程从**画面优先**反转为**配音优先**：
> 先用配音的实际时长决定镜头该多长，再按**视频模型的时长上限**自动切段生成。

## 1. 问题与根因

| 现象 | 根因 |
|---|---|
| 配音被截断 / 提前出现下一句 | 镜头 `duration_sec` 由 AI 一次写死；配音生成后只能"塞进去" |
| 段间重叠（第1段没完第2段就响） | 起点来自字数估算，而非实际音频（已部分修复） |
| 单镜配音超长只能手调 | 没有「按实际音频反推镜头时长」这一步 |
| 生成失败/质量差 | 一个镜头要的时长 > 模型单次输出上限（如 i2v 模型 5s），却按整镜生成 |

## 2. 两个必须并存的约束

1. **配音是时间真值**：每段 `at_sec` + **实际音频时长**（`assets.duration_ms`）不可被压缩；
2. **模型有单次输出上限**：`max_clip_sec` 因模型而异（当前 i2v = 5s，部分模型 15s+），
   且有些模型只支持**固定档位**（如 `duration ∈ {5,10}`）。

→ 结论：镜头时长必须**由配音反推**，而当所需时长超过模型上限时，**把镜头切成多段**（分段生成、成片拼接）。

## 3. 数据模型变更

### 3.1 模型能力（schema 驱动 + 手动覆盖）

`ModelSchemaService` 新增 `duration` 组识别，扫描参数字段名：
`duration | seconds | video_length | length | num_frames | frames | video_duration | clip_seconds`

```jsonc
// 存入 video_model_schema（V15 新增列）
{
  "maxClipSec": 5,                 // 上限（num_frames/fps 也能推）
  "minClipSec": 1,
  "supportedSecs": [5, 10],        // enum 时才有；空 = 支持任意时长
  "paramName": "duration",         // 生成时写哪个字段
  "source": "schema" | "manual"
}
```
- 网关通道（Ark 等）无法探测 → **由模型库手动填**（`video_model_presets.max_clip_sec`，手动优先于 schema）
- 项目级快照 `plan.edit_plan.video_model_max_sec`，便于渲染与体检时不依赖引擎配置

### 3.2 方案侧

```jsonc
// shot 新增/复用
{
  "duration_sec": 8.2,          // 校准后写回 = 配音占用 + 余量
  "tail_sec": 0.3,              // 呼吸余量（项目默认 0.3，可逐镜改）
  "segments": [                 // ← 已有雏形，扩展为显式分段
    { "index": 0, "start_sec": 0.0, "duration_sec": 5.0 },
    { "index": 1, "start_sec": 5.0, "duration_sec": 3.2 }
  ]
}
```

## 4. 校准算法

```
for each shot:
    voiceEnd = max over lines( at_sec + actualOrEstimate )     # 今天已通的链路
    need     = voiceEnd + tail_sec                              # 例：5.2 + 0.3 = 5.5
    cap      = plan.edit_plan.video_model_max_sec               # 例：5.0
    if supportedSecs 非空:                                      # 固定档位模型
        选最小档位 g ≥ need；若 need > 最大档位 → 分段
        segs = 按 g 切（末段 = 余数，若余数 < minClipSec 则并入上一段）
    else if need <= cap:
        segs = [ { 0, need } ]
    else:
        n = ceil(need / cap)
        segs = [ {i, min(cap, need - i*cap)} for i in 0..n-1 ]
    duration_sec = need（成片长度仍是 need，由 n 段拼成）
```

**关键规则**
- **配音位置一律不动**（只改镜头总长与分段）
- **同一镜内的段，成片必须用 cut**（不能 dissolve，否则露出拼接痕迹）
- 段间衔接用「上一段尾帧 = 下一段首帧」（复用现有 `lastFrame` 能力）
- 某段短于模型 `minClipSec` → 提示"该段过短，建议并入相邻段或缩短配音"

## 5. 界面

| 位置 | 内容 |
|---|---|
| 声音卡片（④配音之后） | 「**按配音校准本镜时长**」；显示 `配音 5.2s + 余量 0.3s → 需 8.2s ⇒ 2 段（5.0 + 3.2）` |
| 顶部工具区 | 「**全片按配音校准**」→ 先弹**差异预览表**（哪镜变长/变短、切几段），确认后写入方案（就地保存，不另存版本） |
| 镜头时长网格 | 每格显示 `8.2s · 2 段`，超上限的镜加标记 |
| 模型库 / 引擎配置 | 模型条目新增「单次输出上限(秒)」「支持档位」输入（网关模型必填） |
| 生成前预检 | 生成 keyframe/motion 时若 `需要段数 > 1`，明确提示"将按 N 段生成" |

## 6. 用到的地方加检查（不靠人记）

1. **渲染前音画体检**：列出「配音超出镜头 / 镜头空余 > 1s / 段间无间隙 / 某段 < 模型下限 / 总时长与目标偏差 > 10%」
2. **生成 motion 前**：镜需分段但 `segments` 为空 → 自动先算分段（不再静默按整镜跑）
3. **AI 台词生成**：Prompt 里加"每段字数 ≤ 镜头时长 × 4.5"（从源头减少超长返工）

## 7. 实施顺序

| 步骤 | 内容 | 依赖 |
|---|---|---|
| A | 模型时长上限：schema 识别 + 模型库手动覆盖 + `plan.edit_plan.video_model_max_sec` | 现有 schema 驱动 |
| B | 校准算法（纯函数 + 单测）：`calibrateShots(plan, durations, cap)` | A |
| C | UI：本镜校准 + 全片校准（差异预览）+ 巡检提示 | B |
| D | 分段生成：motion 按 segment 排队，段间尾帧续接 | C |
| E | 渲染：同镜多段用 cut 拼接；音画体检报告 | D |

A~C 是"流程修正"，见效最快；D~E 涉及生成与渲染，随后做。
