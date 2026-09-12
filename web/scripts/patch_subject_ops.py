#!/usr/bin/env python3
"""P13 收尾：①新参考图默认不勾选 ②已选主体只显示定妆照 ③主体操作合并成一个按钮 ④资产库加「定妆」tab。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "PORTRAIT_TAB" in s:
    print("already patched")
    raise SystemExit(0)

# ---------- ④ 资产库「定妆」tab ----------
s = s.replace("""const GAL_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  { key: 'master', label: '成片', kind: 'master', hint: '导出/合成出的成片 master' },""",
              """const GAL_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  { key: 'master', label: '成片', kind: 'master', hint: '导出/合成出的成片 master' },
  { key: 'portrait', label: '定妆', kind: 'portrait', hint: '剧情主体定妆照（subject_portrait）' },""", 1)
s = s.replace("type AudioTab = 'master' | 'voice' | 'bgm' | 'still' | 'clip' | 'all'",
              "type AudioTab = 'master' | 'portrait' | 'voice' | 'bgm' | 'still' | 'clip' | 'all'", 1)
s = s.replace("""function kindTab(kind: string): AudioTab {""",
              """function kindTab(kind: string): AudioTab {
  if (kind === 'portrait') return 'portrait'""", 1)
# 资产按「快照」归类：历史 portrait 资产的 kind 被写成 still
s = s.replace("""const galleryForTab = computed(() =>
  galTab.value === 'all' ? outputAssets.value : outputAssets.value.filter((a) => kindTab(a.kind) === galTab.value),
)""",
              """/** 资产的分类：定妆照优先看快照（历史数据 kind 被写成 still） */
function assetTab(a: AssetRef): AudioTab {
  return a.kind === 'portrait' || a.snapshotKind === 'portrait' ? 'portrait' : kindTab(a.kind)
}
const galleryForTab = computed(() =>
  galTab.value === 'all' ? outputAssets.value : outputAssets.value.filter((a) => assetTab(a) === galTab.value),
)""", 1)
s = s.replace("""    const t = kindTab(a.kind)
    m[t] = (m[t] ?? 0) + 1
  }
  return m
})
const galleryForTab""",
              """    const t = a.kind === 'portrait' || a.snapshotKind === 'portrait' ? 'portrait' : kindTab(a.kind)
    m[t] = (m[t] ?? 0) + 1
  }
  return m
})
const galleryForTab""", 1)
# 定妆照也纳入资产库可见集合
s = s.replace("['still','clip','master','voice','bgm','voice_preview','bgm_preview'].includes(a.kind)",
              "['still','clip','master','voice','bgm','voice_preview','bgm_preview','portrait'].includes(a.kind)", 1)
s = s.replace("kind?: AudioTab", "kind?: AudioTab")

# ---------- ① 新参考图默认不勾选（只作定妆参照，默认不参与锚定） ----------
s = s.replace("""function toggleRefChecked(id: string, on: boolean): void {""",
              """/** 新加入的参考图默认**不勾选**（它只是定妆的参照，默认不参与锚定；要用请显式勾选） */
function markUnchecked(id: string): void {
  if (!refUnchecked.value.includes(id)) {
    refUnchecked.value = [...refUnchecked.value, id]
  }
}

function toggleRefChecked(id: string, on: boolean): void {""", 1)
s = s.replace("""  refSelected.value.push(id)
  syncReferenceAssets()
  message.success('已加入参考图（显示在左侧参考图卡片，可标主体/区域）')""",
              """  refSelected.value.push(id)
  markUnchecked(id)
  syncReferenceAssets()
  message.success('已加入参考图（默认不勾选=不参与锚定；勾选后才参与，它也可作为定妆的参照）')""", 1)
s = s.replace("""      else refSelected.value.push(a.id)""",
              """      else { refSelected.value.push(a.id); markUnchecked(a.id) }""", 1)
s = s.replace("""    if (!refSelected.value.includes(id)) refSelected.value.push(id)""",
              """    if (!refSelected.value.includes(id)) { refSelected.value.push(id); markUnchecked(id) }""", 1)

# ---------- ② 已选主体只显示定妆照（素材图不再当定妆图展示） ----------
s = s.replace("""function subjectThumb(sub: PlanSubject): string {
  if (sub.portraitAssetId && galUrls.value[sub.portraitAssetId]) return galUrls.value[sub.portraitAssetId]
  const id = (sub.refs ?? []).find((r) => r.checked !== false)?.assetId
  if (!id) return ''
  return galUrls.value[id] ?? thumbUrls.value[id] ?? ''
}""",
              """/** 已选主体里显示的图 = **只显示定妆照**（素材图仅作定妆参照，不作为定妆图展示） */
function subjectThumb(sub: PlanSubject): string {
  if (sub.portraitAssetId && galUrls.value[sub.portraitAssetId]) return galUrls.value[sub.portraitAssetId]
  return ''
}""", 1)
s = s.replace("""                  <span v-else class="ref-subject-thumb empty font-mono">无定妆照</span>""",
              """                  <span v-else class="ref-subject-thumb empty font-mono">未定妆</span>""", 1)

# ---------- ③ 主体操作合并成一个按钮（下拉） ----------
old_btns = """                <NButton size="tiny" secondary :loading="portraitBusy" :disabled="!canEdit"
                         :data-testid="`subj-gen-${sub.name}`" title="用该主体勾选的素材图生成标准定妆图"
                         @click="genPortrait(sub.name)">
                  {{ sub.portraitAssetId ? '换一版' : '生成图像' }}
                </NButton>
                <NButton size="tiny" quaternary :disabled="!portraitsOf(sub.name).length"
                         :data-testid="`subj-pick-${sub.name}`" title="把最新一版定妆图设为该主体的锚定图"
                         @click="pickPortrait(sub.name)">
                  选图
                </NButton>
                <NButton size="tiny" quaternary type="error" :disabled="!canEdit"
                         :data-testid="`subj-del-${sub.name}`" title="删除该主体（连同它在参考图上的主体标记）"
                         @click="removeSubject(sub.name)">
                  删除
                </NButton>"""
new_btns = """                <NDropdown
                  trigger="click"
                  size="small"
                  :options="subjectActions(sub)"
                  :disabled="!canEdit"
                  :data-testid="`subj-ops-${sub.name}`"
                  @select="(k: string) => onSubjectAction(k, sub.name)"
                >
                  <NButton size="tiny" secondary :loading="portraitBusy">操作 ▾</NButton>
                </NDropdown>"""
assert s.count(old_btns) == 1
s = s.replace(old_btns, new_btns, 1)

# 操作菜单数据与分发
s = s.replace("""/** 删除主体：连同它在参考图上的主体标记一起清掉（否则会残留成无主参考图） */""",
              """/** 主体操作菜单：生成/换一版 · 选图 · 删除 */
function subjectActions(sub: PlanSubject): Array<{ label: string; key: string; disabled?: boolean }> {
  return [
    { label: sub.portraitAssetId ? '换一版定妆照' : '生成定妆照', key: 'gen' },
    { label: '把最新定妆照设为锚定图', key: 'pick', disabled: !portraitsOf(sub.name).length },
    { label: '删除该主体', key: 'del' },
  ]
}
function onSubjectAction(key: string, name: string): void {
  if (key === 'gen') void genPortrait(name)
  else if (key === 'pick') pickPortrait(name)
  else if (key === 'del') removeSubject(name)
}

/** 删除主体：连同它在参考图上的主体标记一起清掉（否则会残留成无主参考图） */""", 1)
s = s.replace("import { NA, NButton", "import { NA, NButton")  # no-op 占位
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
