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
