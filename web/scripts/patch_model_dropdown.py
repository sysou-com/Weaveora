#!/usr/bin/env python3
"""把模型名改成下拉（库选择）+ 保存到库 / 刷新参数说明 按钮。"""
import io

p = "web/src/views/EngineSettingsView.vue"
s = io.open(p, encoding="utf-8").read()

if 'data-testid="img-model"' not in s or "onPickModel('image'" in s:
    print("already patched")
    raise SystemExit(0)

old_img = '''        <NFormItem label="模型名">
          <NInput v-model:value="imageCloudModel" placeholder="如 black-forest-labs/flux-2-klein-9b（留空用默认）" data-testid="img-model" />
        </NFormItem>'''
new_img = '''        <NFormItem label="模型名（可从模型库选择）">
          <div class="mdl-row">
            <NSelect
              :value="imageCloudModel"
              :options="imageModelOptions"
              size="small"
              class="mdl-select"
              filterable
              tag
              clearable
              placeholder="从库中选择，或输入/粘贴模型名"
              data-testid="img-model"
              @update:value="(v: string | null) => { imageCloudModel = v ?? ''; if (v) onPickModel('image', v) }"
            />
            <NButton size="small" :loading="presetBusy" data-testid="btn-save-model"
                     @click="savePreset('image')">保存到模型库</NButton>
            <NButton size="small" secondary :loading="presetBusy" data-testid="btn-refresh-model"
                     @click="refreshPreset('image')">刷新参数说明</NButton>
            <NButton v-if="imagePresets.length" size="small" quaternary
                     @click="router.push({ path: '/me/engine-settings/models' })">模型库（{{ imagePresets.length }}）</NButton>
          </div>
          <p v-if="imagePresets.length" class="mdl-hint text-secondary">
            库里已存：{{ imagePresets.map((p) => p.model).join('、') }}
          </p>
        </NFormItem>'''
assert s.count(old_img) == 1
s = s.replace(old_img, new_img, 1)

old_vid = '''        <NFormItem label="视频模型">
          <NInput v-model:value="videoCloudModel" placeholder="如 minimax/video-01（留空用默认）" data-testid="vid-model" />
        </NFormItem>'''
new_vid = '''        <NFormItem label="视频模型（可从模型库选择）">
          <div class="mdl-row">
            <NSelect
              :value="videoCloudModel"
              :options="videoModelOptions"
              size="small"
              class="mdl-select"
              filterable
              tag
              clearable
              placeholder="从库中选择，或输入/粘贴模型名"
              data-testid="vid-model"
              @update:value="(v: string | null) => { videoCloudModel = v ?? ''; if (v) onPickModel('video', v) }"
            />
            <NButton size="small" :loading="presetBusy" @click="savePreset('video')">保存到模型库</NButton>
            <NButton size="small" secondary :loading="presetBusy" @click="refreshPreset('video')">刷新参数说明</NButton>
          </div>
        </NFormItem>'''
assert s.count(old_vid) == 1
s = s.replace(old_vid, new_vid, 1)

# 样式
s = s.replace("<style scoped>", """<style scoped>
.mdl-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mdl-select { min-width: 260px; flex: 1 1 260px; }
.mdl-hint { margin: 6px 0 0; font-size: 11.5px; }
""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("template patched")
