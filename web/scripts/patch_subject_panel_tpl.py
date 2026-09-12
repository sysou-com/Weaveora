#!/usr/bin/env python3
"""P13 前端模板：主体列表（勾选/别名/定妆图/生成·换一版·选图）+ 一键生成主体 + 参考图勾选框。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if 'data-testid="subject-list"' in s:
    print("template already patched")
    raise SystemExit(0)

# 1) AssetRef 增加 promptSnapshot（定妆图靠它按主体过滤）
p2 = "web/src/api/types.ts"
t = io.open(p2, encoding="utf-8").read()
if "promptSnapshot" not in t.split("export interface AssetRef")[1].split("}")[0]:
    t = t.replace("""  kind: string
  mime: string
  width: number | null
  height: number | null""", """  kind: string
  mime: string
  width: number | null
  height: number | null
  /** 产物快照（定妆图用它记录 subject / portrait_version） */
  promptSnapshot?: Record<string, unknown> | null""", 1)
    io.open(p2, "w", encoding="utf-8", newline="\n").write(t)
    print("AssetRef.promptSnapshot added")

# 2) 参考图卡片头：加「一键生成主体」
old_head = '''            <div class="brief-head">
              <span class="font-mono eyebrow">参考图</span>
              <label class="upload-link" :class="{ busy: uploadingRef }">
                <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                <span v-if="uploadingRef">上传中…</span>
                <span v-else>+ 上传</span>
              </label>
            </div>'''
new_head = '''            <div class="brief-head">
              <span class="font-mono eyebrow">参考图 / 剧情主体</span>
              <div class="ref-head-ops">
                <button type="button" class="op" :disabled="subjectBusy === 'extract' || !canEdit"
                        data-testid="btn-extract-subjects" @click="onExtractSubjects">
                  {{ subjectBusy === 'extract' ? '抽取中…' : '一键生成主体' }}
                </button>
                <label class="upload-link" :class="{ busy: uploadingRef }">
                  <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                  <span v-if="uploadingRef">上传中…</span>
                  <span v-else>+ 上传</span>
                </label>
              </div>
            </div>

            <!-- P13：剧情主体列表（勾选=参与锚定；定妆图=一致性锚定图） -->
            <div v-if="planSubjects().length" class="subj-list" data-testid="subject-list">
              <div v-for="sub in planSubjects()" :key="sub.name" class="subj-row" :data-testid="`subj-${sub.name}`">
                <label class="subj-check" :title="sub.enabled === false ? '未勾选 = 不参与锚定' : '参与锚定'">
                  <input type="checkbox" :checked="sub.enabled !== false"
                         @change="toggleSubject(sub.name, ($event.target as HTMLInputElement).checked)" />
                </label>
                <span class="subj-name">{{ sub.name }}</span>
                <span v-if="sub.aliases?.length" class="subj-alias font-mono" :title="sub.aliases?.join('、')">
                  {{ sub.aliases?.slice(0, 2).join('/') }}<template v-if="(sub.aliases?.length ?? 0) > 2">…</template>
                </span>
                <span :class="['subj-portrait', 'font-mono', { on: !!sub.portraitAssetId }]">
                  {{ sub.portraitAssetId ? `定妆图 v${sub.portraitVersion ?? 1}` : '定妆图 ✗' }}
                </span>
                <span class="subj-refs font-mono" :title="'该主体的素材参考图张数'">
                  {{ (sub.refs ?? []).length }} 图
                </span>
                <NButton size="tiny" secondary :loading="portraitBusy" :disabled="!canEdit"
                         :data-testid="`subj-gen-${sub.name}`" title="用该主体勾选的素材图生成标准定妆图"
                         @click="genPortrait(sub.name)">
                  {{ sub.portraitAssetId ? '换一版' : '生成图像' }}
                </NButton>
                <NButton size="tiny" quaternary :disabled="!portraitsOf(sub.name).length"
                         :data-testid="`subj-pick-${sub.name}`" title="把最新一版定妆图设为该主体的锚定图"
                         @click="pickPortrait(sub.name)">
                  选图
                </NButton>
              </div>
              <p class="subj-hint text-secondary">
                勾选 = 参与锚定；分镜锚定用「定妆图」优先，没有定妆图才用素材图。改动后请<b>保存并重新确认</b>，否则生成仍读旧稿。
              </p>
            </div>'''
assert s.count(old_head) == 1
s = s.replace(old_head, new_head, 1)

# 3) 参考图缩略图：加勾选框（是否参与参考）
old_thumb = '''                :class="['ref-thumb', { sel: refSelected.includes(a.id) }]"
                :title="refSelected.includes(a.id) ? '点击取消' : '点击用作参考'"
                @click="toggleRef(a.id, !refSelected.includes(a.id))"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <i v-if="refSelected.includes(a.id)" class="ref-badge font-mono">REF</i>'''
new_thumb = '''                :class="['ref-thumb', { sel: refSelected.includes(a.id), off: refUnchecked.includes(a.id) }]"
                :title="refSelected.includes(a.id) ? '点击取消加入' : '点击用作参考'"
                @click="toggleRef(a.id, !refSelected.includes(a.id))"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <!-- P13：勾选=参与参考（未勾选视为不作为参考） -->
                <label v-if="refSelected.includes(a.id)" class="ref-check" title="勾选=参与参考；取消勾选=不参与（不必删除）"
                       @click.stop>
                  <input type="checkbox" :checked="!refUnchecked.includes(a.id)"
                         :data-testid="`ref-check-${a.id.slice(0, 8)}`"
                         @change="toggleRefChecked(a.id, ($event.target as HTMLInputElement).checked)" />
                </label>
                <i v-else class="ref-badge font-mono">REF</i>'''
assert s.count(old_thumb) == 1
s = s.replace(old_thumb, new_thumb, 1)

# 4) 样式
s = s.replace("<style scoped>", """<style scoped>
.ref-head-ops { display: inline-flex; align-items: center; gap: 6px; }
.subj-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 6px 0 8px;
  padding: 6px 8px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface-sunken);
}
.subj-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; flex-wrap: wrap; }
.subj-check { display: inline-flex; align-items: center; }
.subj-name { font-weight: 500; }
.subj-alias { font-size: 10.5px; color: var(--wv-text-4); }
.subj-portrait { font-size: 10.5px; color: var(--wv-text-4); }
.subj-portrait.on { color: var(--wv-success, #7BC47F); }
.subj-refs { font-size: 10.5px; color: var(--wv-text-4); margin-right: auto; }
.subj-hint { margin: 2px 0 0; font-size: 11px; line-height: 1.6; }
.ref-thumb.off img { opacity: 0.35; filter: grayscale(0.7); }
.ref-check { position: absolute; top: 4px; left: 4px; z-index: 2; }
.ref-check input { width: 14px; height: 14px; }
""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("template patched")
