#!/usr/bin/env python3
"""ModelSchemaPanel：只读参数可移到「全局参数」变可编辑（拖拽 + 一键移动；按模型记忆）。幂等。"""
import io

p = "web/src/components/engine/ModelSchemaPanel.vue"
s = io.open(p, encoding="utf-8").read()
if "promoted" in s:
    print("already patched")
    raise SystemExit(0)

# 1) 状态与逻辑
old = """const mapping = computed(() => props.schema?.mapping ?? {})"""
new = """const mapping = computed(() => props.schema?.mapping ?? {})

/**
 * 「只读 → 全局参数」提升名单。
 *
 * 各模型 schema 会把一部分参数标成不可调（例如 seed 由系统填、结构复杂字段），
 * 但实际使用中用户可能就是想改它 —— 这里允许把任意只读参数**移到全局参数**里变成可编辑
 * （拖拽到「全局参数」区域，或点行尾的「→ 可编辑」）。
 * 名单按 (kind + 模型) 存在本地，切回该模型仍然记得。
 */
const PROMOTE_KEY = computed(() => `weaveora.editableParams:${props.kind}:${props.schema?.model ?? ''}`)
const promoted = ref<string[]>([])
function loadPromoted(): void {
  try {
    const raw = localStorage.getItem(PROMOTE_KEY.value)
    promoted.value = raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    promoted.value = []
  }
}
function savePromoted(): void {
  try {
    localStorage.setItem(PROMOTE_KEY.value, JSON.stringify(promoted.value))
  } catch {
    // 隐私模式等写不了就算了，本次会话内仍可用
  }
}
watch([() => props.schema?.model, () => props.kind], loadPromoted, { immediate: true })
const dragName = ref('')
function promote(name: string): void {
  if (!promoted.value.includes(name)) {
    promoted.value = [...promoted.value, name]
    savePromoted()
    message.info(`「${name}」已移到全局参数，可直接改值`)
  }
  dragName.value = ''
}
function demote(name: string): void {
  promoted.value = promoted.value.filter((x) => x !== name)
  savePromoted()
  clearOne(params.value.find((x) => x.name === name) as ModelSchemaParam)
}"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# 2) editable 计算：包含被提升的
old2 = """const editable = computed(() => params.value.filter((p) => p.userEditable))
const quality = computed(() => editable.value.filter((p) => p.group === 'quality'))
const control = computed(() => editable.value.filter((p) => p.group === 'control' || p.group === 'other'))"""
new2 = """/** 可编辑 = schema 标了 userEditable + 用户从只读里移过来的 */
const isEditable = (p: ModelSchemaParam): boolean => !!p.userEditable || promoted.value.includes(p.name)
const editable = computed(() => params.value.filter(isEditable))
const readOnly = computed(() => params.value.filter((p) => !isEditable(p)))
const quality = computed(() => editable.value.filter((p) => p.group === 'quality' || !p.userEditable))
const control = computed(() =>
  editable.value.filter((p) => p.userEditable && (p.group === 'control' || p.group === 'other')),
)"""
assert s.count(old2) == 1
s = s.replace(old2, new2, 1)

# 3) 只读行：可拖拽 + 「→ 可编辑」按钮
old3 = """          <span v-if="p.required" class="ms-req">必填</span>
          <span v-if="p.desc" class="ms-desc text-secondary" :title="p.desc">{{ p.desc }}</span>
        </li>"""
new3 = """          <span v-if="p.required" class="ms-req">必填</span>
          <span v-if="p.desc" class="ms-desc text-secondary" :title="p.desc">{{ p.desc }}</span>
          <button
            type="button"
            class="ms-move"
            draggable="true"
            :disabled="disabled"
            :data-testid="`promote-${p.name}`"
            title="拖到「全局参数」区域，或点这里 —— 移到全局参数后即可改值"
            @dragstart="dragName = p.name"
            @click="promote(p.name)"
          >
            → 可编辑
          </button>
        </li>"""
assert s.count(old3) == 1
s = s.replace(old3, new3, 1)

# 4) 全局参数区：作为拖放目标 + 说明
old4 = """      <div v-if="editable.length" class="ms-global">"""
new4 = """      <div
        class="ms-global"
        :class="{ drop: !!dragName }"
        data-testid="global-params-drop"
        @dragover.prevent
        @drop.prevent="dragName && promote(dragName)"
      >"""
assert s.count(old4) == 1
s = s.replace(old4, new4, 1)
# 空的时候也要能接收拖放
s = s.replace("""      <p v-else class="ms-empty text-secondary">该模型没有可调参数。</p>""",
              """      <p v-else class="ms-empty text-secondary">
        该模型没有可调参数 —— 可从上方只读列表把参数<b>拖到这里</b>（或点「→ 可编辑」）变成可调。
      </p>""", 1)

# 5) 编辑行：被提升的显示「← 只读」，普通可编辑行显示「清」
old5 = """          <NButton v-if="hasOverride(p)" size="tiny" quaternary :disabled="disabled" @click="clearOne(p)">
            清
          </NButton>"""
new5 = """          <NButton v-if="hasOverride(p)" size="tiny" quaternary :disabled="disabled" @click="clearOne(p)">
            清
          </NButton>
          <NButton
            v-if="!p.userEditable"
            size="tiny"
            quaternary
            :disabled="disabled"
            :data-testid="`demote-${p.name}`"
            title="移回只读（该参数由系统按上面配置决定）"
            @click="demote(p.name)"
          >
            ← 只读
          </NButton>"""
assert s.count(old5) == 1
s = s.replace(old5, new5, 1)

# 6) watch 补 import
s = s.replace("import { computed, ref, watch } from 'vue'", "import { computed, ref, watch } from 'vue'", 1)

# 7) 样式
s = s.replace("<style scoped>", """<style scoped>
.ms-move {
  margin-left: auto;
  appearance: none;
  border: 1px dashed var(--wv-line);
  background: transparent;
  color: var(--wv-text-4);
  font-size: 11px;
  border-radius: 6px;
  padding: 1px 6px;
  cursor: grab;
}
.ms-move:hover { color: var(--wv-accent-text); border-color: var(--wv-accent); }
.ms-global.drop {
  outline: 1px dashed var(--wv-accent);
  outline-offset: 2px;
  border-radius: 8px;
}
""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ModelSchemaPanel patched")
