#!/usr/bin/env python3
"""参考图卡片：已选块 → 勾选主体 + 定妆照（只读名字、去取消/删除、只编区域）；并挪到位置预览下方。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "subject-ref-block" in s:
    print("already patched")
    raise SystemExit(0)

start = s.index('            <div v-if="selectedRefAssets.length" class="ref-subjects">')
end = s.index('            <p v-if="refSelected.length" class="ref-count font-mono">')
old_block = s[start:end]

new_block = '''            <!-- P13：勾选的主体（显示定妆照，名字只读，只编区域；取消勾选即从显示中移除） -->
            <div v-if="enabledSubjects.length" class="ref-subjects" data-testid="subject-ref-block">
              <p class="ref-subjects-title font-mono">已选主体<span class="text-secondary">（勾选参与的主体；只可编辑区域）</span></p>
              <div v-for="sub in enabledSubjects" :key="sub.name" class="ref-subject-block">
                <div class="ref-subject-row">
                  <img v-if="subjectThumb(sub)" :src="subjectThumb(sub)" class="ref-subject-thumb" alt="" />
                  <span v-else class="ref-subject-thumb empty font-mono">无定妆照</span>
                  <span class="ref-subject-name">{{ sub.name }}</span>
                  <span v-if="sub.portraitAssetId" class="ref-subject-tag font-mono">定妆照 v{{ sub.portraitVersion ?? 1 }}</span>
                  <span v-else class="ref-subject-tag off font-mono">素材图</span>
                </div>
                <div class="ref-region-row">
                  <span class="ref-region-label font-mono">区域%</span>
                  <input
                    v-for="k in (['x', 'y', 'w', 'h'] as const)"
                    :key="k"
                    class="text ref-region-input"
                    type="text"
                    inputmode="numeric"
                    :placeholder="k"
                    :value="subjectRegion(sub, k)"
                    @input="setSubjectRegion(sub, k, ($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>
            </div>
'''
s = s[:start] + new_block + s[end:]

# 位置预览卡片里，把「已选/超限提示」搬到描述下方 → 这里保留一个容器，稍后在模板中插入
anchor = '''            <p v-if="cameraIntentWithRefs" class="ref-conflict">
              检测到「背影/过肩/机位」类构图诉求：参考图可能把构图拉回参考视角。建议先取消勾选参考图（仅需形象/画风锚定时再选），或把机位写进「视角/前景/主体朝向」字段。
            </p>
          </div>'''
assert s.count(anchor) == 1
s = s.replace(anchor, '''          </div>

          <!-- 计数与提示放到「位置预览」描述下方 -->
          <div class="ref-meta">
            <p v-if="refSelected.length" class="ref-count font-mono" data-testid="ref-count">
              {{ refSelected.length }}/{{ MAX_REFS }} 已选
            </p>
            <p v-if="refOverModelLimit" class="ref-conflict" data-testid="ref-model-limit">
              当前图片模型「{{ modelRefHint.name }}」最多接收 {{ modelRefHint.max }} 张参考图，
              本方案已绑定 {{ refSelected.length }} 张 —— 超出的会被自动丢弃（多主体镜头建议改用
              支持多图的模型，如 bytedance/seedream-4 / google/nano-banana：image_input）。
            </p>
            <p v-if="cameraIntentWithRefs" class="ref-conflict">
              检测到「背影/过肩/机位」类构图诉求：参考图可能把构图拉回参考视角。建议先取消勾选参考图（仅需形象/画风锚定时再选），或把机位写进「视角/前景/主体朝向」字段。
            </p>
          </div>''', 1)

# 计算属性/工具函数
s = s.replace('''/** 位置预览数据：主体名 + 区域 + 配色 */''', '''/** P13：勾选参与的主体（用于「已选主体」显示：定妆照 + 区域） */
const enabledSubjects = computed<PlanSubject[]>(() =>
  planSubjects().filter((s) => s.enabled !== false && (s.name ?? '').trim() !== ''),
)
/** 该主体显示的图：定妆照优先，其次第一张勾选素材图 */
function subjectThumb(sub: PlanSubject): string {
  if (sub.portraitAssetId && galUrls.value[sub.portraitAssetId]) return galUrls.value[sub.portraitAssetId]
  const id = (sub.refs ?? []).find((r) => r.checked !== false)?.assetId
  return id ? (galUrls.value[id] ?? thumbUrls[id] ?? '') : ''
}
/** 主体区域（沿用素材图上的 region；没有则空） */
function subjectRegion(sub: PlanSubject, k: 'x' | 'y' | 'w' | 'h'): string {
  const id = sub.portraitAssetId || (sub.refs ?? []).find((r) => r.checked !== false)?.assetId || ''
  const src = id ? refRegions.value[id] : undefined
  if (src) return src[k] ?? ''
  const r = (sub.refs ?? []).find((x) => r0(x))?.region
  const pct = r ? pctOf(r, k) : ''
  return pct
}
function r0(x: PlanSubjectRef): boolean {
  return x.checked !== false
}
/** 归一化 region（0–1）→ 百分比字符串 */
function pctOf(r: { x: number; y: number; w: number; h: number }, k: 'x' | 'y' | 'w' | 'h'): string {
  const v = r[k]
  return Number.isFinite(v) ? String(Math.round(v * 100)) : ''
}
/** 编辑主体区域：写回该主体第一张勾选素材图（或定妆图）对应的 refRegions，并同步进方案 */
function setSubjectRegion(sub: PlanSubject, k: 'x' | 'y' | 'w' | 'h', v: string): void {
  const id = sub.portraitAssetId || (sub.refs ?? []).find((r) => r.checked !== false)?.assetId || ''
  if (!id) {
    message.warning(`「${sub.name}」没有可挂区域的图（先上传/勾选素材图或生成定妆照）`)
    return
  }
  setRefRegion(id, k, v)
}

/** 位置预览数据：主体名 + 区域 + 配色 */''', 1)

# 样式
s = s.replace("<style scoped>", """<style scoped>
.ref-subject-name { font-size: 13px; }
.ref-subject-tag { font-size: 10.5px; color: var(--wv-success, #7BC47F); }
.ref-subject-tag.off { color: var(--wv-text-4); }
.ref-subject-thumb.empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9.5px;
  color: var(--wv-text-4);
  background: var(--wv-surface-sunken);
}
.ref-meta { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
