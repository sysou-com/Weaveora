<script setup lang="ts">
/**
 * P12 模型「调用说明」+「全局参数」面板。
 *
 * 为什么需要它：云 API 模型的入参名各不相同（同样是参考图，flux-2-klein-9b 叫 `images`，
 * 别的叫 `input_images`/`image`），而 Replicate 对**未知字段是静默忽略**的 —— 猜错不会报错，
 * 只是「参考图完全没生效、一致性全无」。所以配完模型要能直接看到它到底认哪些参数。
 */
import { NAlert, NButton, NInput, NInputNumber, NSelect, NSwitch, NTag, useMessage } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import { refreshModelSchemas } from '@/api/engineSettings'
import type { EngineSettings, ModelSchema, ModelSchemaParam } from '@/api/types'

const props = defineProps<{
  kind: 'image' | 'video'
  schema: ModelSchema | null
  /** 用户当前设置的全局参数 */
  params: Record<string, unknown> | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 全局参数变更（父级负责保存） */
  'update:params': [v: Record<string, unknown>]
  /** schema 刷新后回填（父级更新本地副本） */
  refreshed: [s: EngineSettings]
}>()

const message = useMessage()
const busy = ref(false)
const openDetail = ref(false)

/** 本地参数副本（父级保存时带上） */
const local = ref<Record<string, unknown>>({ ...(props.params ?? {}) })
watch(
  () => props.params,
  (v) => { local.value = { ...(v ?? {}) } },
)

const mapping = computed(() => props.schema?.mapping ?? {})
const params = computed(() => props.schema?.params ?? [])
const refsField = computed(() => String(mapping.value.refs ?? ''))
const refsIsArray = computed(() => mapping.value.refsIsArray === true)
const refsMax = computed(() => Number(mapping.value.refsMax ?? 0))
const lacksRefs = computed(() => !!props.schema && !refsField.value)

/** 给用户改的：schema 里标了 userEditable 的标量参数（画质等） */
const editable = computed(() => params.value.filter((p) => p.userEditable))
const quality = computed(() => editable.value.filter((p) => p.group === 'quality'))
const control = computed(() => editable.value.filter((p) => p.group === 'control' || p.group === 'other'))

const GROUP_LABEL: Record<string, string> = {
  refs: '参考图',
  prompt: '提示词',
  quality: '画质',
  control: '控制',
  other: '其他',
}

function val(p: ModelSchemaParam): unknown {
  const v = local.value[p.name]
  return v === undefined || v === null ? p.default : v
}
function setVal(p: ModelSchemaParam, v: unknown): void {
  const next = { ...local.value }
  if (v === null || v === undefined) delete next[p.name]
  else next[p.name] = v
  local.value = next
  emit('update:params', next)
}
function hasOverride(p: ModelSchemaParam): boolean {
  return local.value[p.name] !== undefined && local.value[p.name] !== null
}
function clearOne(p: ModelSchemaParam): void {
  setVal(p, null)
}
/** 恢复全部默认（清空覆盖，交回模型默认值） */
function resetAll(): void {
  local.value = {}
  emit('update:params', {})
  message.info('已恢复模型默认参数（保存后生效）')
}

function fldType(p: ModelSchemaParam): string {
  if (p.type === 'enum') return 'enum'
  if (p.type === 'array') return 'array'
  return p.type || 'string'
}
function defText(p: ModelSchemaParam): string {
  if (p.default === undefined) return '—'
  return typeof p.default === 'object' ? JSON.stringify(p.default) : String(p.default)
}
function enumOptions(p: ModelSchemaParam): Array<{ label: string; value: string }> {
  return (p.enum ?? []).map((v) => ({ label: v, value: v }))
}

async function refresh(): Promise<void> {
  busy.value = true
  try {
    const s = await refreshModelSchemas(props.kind)
    emit('refreshed', s)
    const sch = props.kind === 'image' ? s.imageModelSchema : s.videoModelSchema
    const n = sch?.mapping?.refs ? String(sch.mapping.refs) : '（无）'
    message.success(`已获取模型调用参数：${sch?.params?.length ?? 0} 个参数，参考图字段 = ${n}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '获取模型参数失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="ms">
    <div class="ms-head">
      <span class="ms-title font-mono">调用参数说明</span>
      <NTag v-if="schema?.version" size="small" :bordered="false" class="ms-ver">
        v{{ String(schema.version).slice(0, 8) }}
      </NTag>
      <NTag v-if="refsField" size="small" type="success" :bordered="false">
        参考图字段 {{ refsField }}{{ refsIsArray ? `[]${refsMax ? ` ≤${refsMax}` : ''}` : '（单图）' }}
      </NTag>
      <NTag v-else-if="schema" size="small" type="warning" :bordered="false">该模型不支持参考图</NTag>
      <span class="ms-spacer" />
      <NButton size="tiny" secondary :loading="busy" :disabled="disabled" data-testid="btn-refresh-schema" @click="refresh">
        刷新参数说明
      </NButton>
      <NButton v-if="schema" size="tiny" quaternary @click="openDetail = !openDetail">
        {{ openDetail ? '收起明细' : '查看全部参数' }}
      </NButton>
    </div>

    <p v-if="!schema" class="ms-empty text-secondary">
      还没获取过该模型的参数说明 —— 保存配置时会自动拉取；也可点右上「刷新参数说明」。
    </p>

    <template v-else>
      <NAlert v-if="lacksRefs" type="warning" :show-icon="false" class="ms-alert">
        这个模型没有参考图入口：人物/场景一致性无法保证（关键帧不会参考你上传的参考图）。
        建议用支持参考图的模型（如 <code>black-forest-labs/flux-2-klein-9b</code>，参考图字段 <code>images</code>）。
      </NAlert>
      <NAlert v-for="n in schema.notes" :key="n" type="info" :show-icon="false" class="ms-alert">{{ n }}</NAlert>

      <ul class="ms-list">
        <li v-for="p in params" :key="p.name" class="ms-item">
          <span class="ms-name font-mono">{{ p.name }}</span>
          <span class="ms-type font-mono">{{ fldType(p) }}</span>
          <span class="ms-def">默认 {{ defText(p) }}</span>
          <NTag size="tiny" :bordered="false" class="ms-group">{{ GROUP_LABEL[p.group ?? 'other'] }}</NTag>
          <span v-if="p.required" class="ms-req">必填</span>
          <span v-if="p.desc" class="ms-desc text-secondary" :title="p.desc">{{ p.desc }}</span>
        </li>
      </ul>

      <div v-if="editable.length" class="ms-global">
        <div class="ms-global-head">
          <span class="font-mono">全局参数</span>
          <span class="text-secondary ms-tip">留空 = 用模型默认值；这些会应用到所有关键帧/片段</span>
          <NButton size="tiny" quaternary :disabled="disabled" @click="resetAll">恢复默认</NButton>
        </div>

        <div v-for="p in [...quality, ...control]" :key="p.name" class="ms-row">
          <span class="ms-label">
            {{ p.name }}
            <span v-if="hasOverride(p)" class="ms-over" title="已覆盖模型默认值">已改</span>
          </span>

          <NSelect
            v-if="p.type === 'enum'"
            :value="val(p) as string"
            :options="enumOptions(p)"
            size="small"
            class="ms-input"
            clearable
            :placeholder="`默认 ${defText(p)}`"
            :disabled="disabled"
            @update:value="(v: string | null) => setVal(p, v)"
          />
          <NInputNumber
            v-else-if="p.type === 'integer' || p.type === 'number'"
            :value="(val(p) as number | null) ?? null"
            :max="p.max"
            :precision="p.type === 'integer' ? 0 : undefined"
            size="small"
            class="ms-input"
            :placeholder="`默认 ${defText(p)}`"
            :disabled="disabled"
            @update:value="(v: number | null) => setVal(p, v)"
          />
          <NSwitch
            v-else-if="p.type === 'boolean'"
            :value="Boolean(val(p))"
            size="small"
            :disabled="disabled"
            @update:value="(v: boolean) => setVal(p, v)"
          />
          <NInput
            v-else
            :value="(val(p) as string) ?? ''"
            size="small"
            class="ms-input"
            :placeholder="`默认 ${defText(p)}`"
            :disabled="disabled"
            @update:value="(v: string) => setVal(p, v)"
          />
          <NButton v-if="hasOverride(p)" size="tiny" quaternary :disabled="disabled" @click="clearOne(p)">
            清
          </NButton>
        </div>
      </div>

      <p v-else class="ms-empty text-secondary">该模型没有可调参数。</p>
    </template>
  </div>
</template>

<style scoped>
.ms {
  margin: 4px 0 10px;
  padding: 10px 12px;
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m, 10px);
  background: var(--wv-surface-sunken);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ms-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ms-title { font-size: 12px; color: var(--wv-text-3); }
.ms-ver { opacity: 0.8; }
.ms-spacer { flex: 1 1 auto; }
.ms-empty { margin: 0; font-size: 12.5px; }
.ms-alert { margin: 0; }
.ms-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 210px;
  overflow: auto;
}
.ms-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  line-height: 1.7;
}
.ms-name { color: var(--wv-text-2); min-width: 150px; }
.ms-type { color: var(--wv-text-4); font-size: 11px; min-width: 52px; }
.ms-def { color: var(--wv-text-4); font-size: 11px; min-width: 84px; }
.ms-group { opacity: 0.75; }
.ms-req { color: var(--wv-danger, #d9534f); font-size: 11px; }
.ms-desc { flex: 1 1 auto; font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ms-global {
  border-top: 1px dashed var(--wv-line);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ms-global-head { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.ms-tip { font-size: 11.5px; flex: 1 1 auto; }
.ms-row { display: flex; align-items: center; gap: 10px; }
.ms-label { font-size: 12px; min-width: 150px; color: var(--wv-text-2); }
.ms-over {
  margin-left: 6px;
  font-size: 10px;
  color: var(--wv-accent-text, #d0a24e);
}
.ms-input { max-width: 220px; }
@media (max-width: 640px) {
  .ms-name, .ms-label { min-width: 96px; }
  .ms-desc { display: none; }
  .ms-input { max-width: none; flex: 1 1 auto; }
}
</style>
