#!/usr/bin/env python3
"""P13 收尾2：参考图默认不选中且生成后复位；主体操作加「管理别名」；剧情主体加模糊搜索。幂等。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "subjectQuery" in s:
    print("already patched")
    raise SystemExit(0)

# ---------- ① 参考图格子高亮 = 勾选状态（默认不选中；用完复位） ----------
s = s.replace(""":class="['ref-thumb', { sel: refSelected.includes(a.id), off: refUnchecked.includes(a.id) }]\"""",
              """:class="['ref-thumb', { sel: refSelected.includes(a.id) && !refUnchecked.includes(a.id), off: refUnchecked.includes(a.id) }]\"""", 1)

# 生成定妆后 → 该主体用过的参考图复位为“未选中”
s = s.replace("""    await refreshGallery()
    message.success(`「${name}」定妆图已生成 —— 点「选图」把它设为该主体的锚定图`)""",
              """    await refreshGallery()
    // 参考图只是“生成定妆的素材”：用完即复位为未勾选（不再当作锚定图）
    const sub = planSubjects().find((x) => x.name === name)
    for (const r of sub?.refs ?? []) markUnchecked(r.assetId)
    syncReferenceAssets()
    message.success(`「${name}」定妆图已生成（参考图已复位为未选中）—— 用「操作 ▾ → 把最新定妆照设为锚定图」`)""", 1)

# ---------- ② 主体操作加「管理别名…」+ 别名管理弹窗状态 ----------
s = s.replace("""    { label: '删除该主体', key: 'del' },
  ]""",
              """    { label: '管理别名…', key: 'alias' },
    { label: '删除该主体', key: 'del' },
  ]""", 1)
s = s.replace("""  else if (key === 'del') removeSubject(name)
}""",
              """  else if (key === 'del') removeSubject(name)
  else if (key === 'alias') openAlias(name)
}

/** 别名管理：LLM 抽的别名可能张冠李戴（如把「浅蔷薇色纱衣女子」当成秦可卿），要能删 */
const aliasOpen = ref(false)
const aliasSubject = ref('')
const aliasDraft = ref('')
function openAlias(name: string): void {
  aliasSubject.value = name
  aliasDraft.value = ''
  aliasOpen.value = true
}
function removeAlias(name: string, alias: string): void {
  const subs = planSubjects().map((x) =>
    x.name === name ? { ...x, aliases: (x.aliases ?? []).filter((a) => a !== alias) } : x,
  )
  setPlanSubjects(subs)
  message.success(`已删除「${name}」的别名「${alias}」`)
}
function addAlias(name: string): void {
  const v = aliasDraft.value.trim()
  if (!v) return
  const subs = planSubjects().map((x) =>
    x.name === name ? { ...x, aliases: Array.from(new Set([...(x.aliases ?? []), v])) } : x,
  )
  setPlanSubjects(subs)
  aliasDraft.value = ''
  message.success(`已为「${name}」添加别名「${v}」`)
}

/** 剧情主体搜索（按名字 / 别名模糊过滤） */
const subjectQuery = ref('')
function filteredSubjects(): PlanSubject[] {
  const q = subjectQuery.value.trim().toLowerCase()
  const all = planSubjects()
  if (!q) return all
  return all.filter(
    (s) => s.name.toLowerCase().includes(q) || (s.aliases ?? []).some((a) => a.toLowerCase().includes(q)),
  )
}""", 1)

# ---------- ③ 剧情主体：搜索框 + 过滤 + 限高滚动 ----------
s = s.replace("""            <p class="ref-group-title font-mono">剧情主体：</p>
            <div v-if="planSubjects().length" class="subj-list" data-testid="subject-list">
              <div v-for="sub in planSubjects()" :key="sub.name" class="subj-row" :data-testid="`subj-${sub.name}`">""",
              """            <p class="ref-group-title font-mono">剧情主体：</p>
            <div v-if="planSubjects().length" class="subj-wrap" data-testid="subject-list">
              <input
                v-model="subjectQuery"
                class="text subj-search"
                type="text"
                placeholder="搜索主体（名字 / 别名）"
                data-testid="subj-search"
              />
              <div class="subj-list">
              <div v-for="sub in filteredSubjects()" :key="sub.name" class="subj-row" :data-testid="`subj-${sub.name}`">""", 1)
s = s.replace("""              <p class="subj-hint text-secondary">
                勾选 = 参与锚定；分镜锚定用「定妆图」优先，没有定妆图才用素材图。改动后请<b>保存并重新确认</b>，否则生成仍读旧稿。
              </p>
            </div>""",
              """              </div>
              <p class="subj-hint text-secondary">
                勾选 = 参与锚定；锚定优先用「定妆图」，没有定妆图才用素材图。改动后请<b>保存并重新确认</b>，否则生成仍读旧稿。
              </p>
            </div>""", 1)

# 别名弹窗（放在页面级：紧跟 ShotPickerDialog 之前）
s = s.replace("""      <!-- P12：分镜勾选弹窗（>3 镜时生成前先选镜 + 可顺手封版） -->""",
              """      <!-- P13：别名管理（LLM 可能把某个别名认错主体，需能删） -->
      <NModal
        v-model:show="aliasOpen"
        preset="card"
        :title="`「${aliasSubject}」的别名`"
        style="max-width: 460px"
        data-testid="alias-modal"
      >
        <p class="alias-tip text-secondary">
          这些别名来自「一键生成主体」，用于分镜里识别同一主体。若某个别名其实不是这个主体（例如把「浅蔷薇色纱衣女子」错认成秦可卿），删掉它即可。
        </p>
        <div class="alias-list">
          <div
            v-for="a in (planSubjects().find((x) => x.name === aliasSubject)?.aliases ?? [])"
            :key="a"
            class="alias-row"
          >
            <span class="alias-name">{{ a }}</span>
            <NButton size="tiny" quaternary type="error" @click="removeAlias(aliasSubject, a)">删除</NButton>
          </div>
          <p v-if="!(planSubjects().find((x) => x.name === aliasSubject)?.aliases ?? []).length" class="text-secondary alias-tip">
            暂无别名
          </p>
        </div>
        <div class="alias-add">
          <NInput v-model:value="aliasDraft" size="small" placeholder="补一个别名，回车添加" @keyup.enter="addAlias(aliasSubject)" />
          <NButton size="small" secondary @click="addAlias(aliasSubject)">添加</NButton>
        </div>
      </NModal>

      <!-- P12：分镜勾选弹窗（>3 镜时生成前先选镜 + 可顺手封版） -->""", 1)

# 样式
s = s.replace("<style scoped>", """<style scoped>
.subj-wrap { display: flex; flex-direction: column; gap: 6px; margin: 6px 0 8px; }
.subj-search {
  width: 100%;
  padding: 4px 8px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface);
  color: inherit;
  font-size: 12.5px;
}
.subj-list { max-height: 240px; overflow: auto; }
.alias-tip { margin: 0 0 8px; font-size: 12px; line-height: 1.8; }
.alias-list { display: flex; flex-direction: column; gap: 4px; max-height: 220px; overflow: auto; }
.alias-row {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 4px 8px; border: 1px solid var(--wv-line); border-radius: 8px;
  background: var(--wv-surface-sunken); font-size: 12.5px;
}
.alias-add { display: flex; gap: 8px; margin-top: 10px; }
""", 1)

# NInput 引入
if " NInput," not in s and "NInput " not in s.split("from 'naive-ui'")[0]:
    s = s.replace("import { NAlert, NButton, NDropdown,", "import { NAlert, NButton, NDropdown, NInput,", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
