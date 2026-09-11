#!/usr/bin/env python3
"""P12 一次性的模板重排脚本（VideoPlanEditor.vue）。

做三件事：
 1. 把「主题」卡片里的音频相关 template 整块摘出来（后面重组）
 2. 镜头时长：一行一镜 → 自适应网格（桌面多列，手机 1~2 列）
 3. 角色绑定 / 配乐 / 旁白 三个分散 section → 合并为一个「声音」卡片，顺序：音色 → 绑定 → 配音 → 配乐
并且把「试听配音(改名后叫音色试听) + 重录/改名/删除」移到「配音音色」下拉框后面。

脚本带断言：找不到标记就报错退出，避免“改了一半”。
"""
import re
import sys

P = "src/components/director/VideoPlanEditor.vue"
src = open(P, encoding="utf-8").read()
orig = src


def must(cond, msg):
    if not cond:
        print("FAIL: " + msg)
        sys.exit(1)


# ---------- 1) 摘出音频 template 块 ----------
# 注意：必须**行首锚定**（\n + 6 空格）。否则 12 空格的 </template> 里也包含
# "      </template>" 子串，会提前截断（已踩）。
start = src.index('\n      <template v-if="props.plan.audio">') + 1
end = src.index('\n      </template>\n', start) + len('\n      </template>\n')
audio_block = src[start:end]
must('试听配音' in audio_block, '音频块里应含试听配音按钮')
must('BGM 情绪' in audio_block, '音频块里应含 BGM 情绪')
src = src[:start] + src[end:]

# 注意：不要做“顺手去掉空行”的整块 replace —— 它会把编辑设定卡片的 </template> 一起吃掉（已踩过坑）

# ---------- 2) 镜头时长 → 网格 ----------
old_dur = re.search(
    r'      <div\n        v-for="shot in props\.plan\.shots"\n        :key="shot\.shot_no"\n        class="narration-row"\n      >\n'
    r'.*?\n      </div>\n    </section>\n',
    src, re.S)
must(old_dur, '找不到镜头时长列表')
new_dur = """      <div class="dur-grid">
        <label v-for="shot in props.plan.shots" :key="shot.shot_no" class="dur-cell">
          <span class="key">第 {{ shot.shot_no }} 镜</span>
          <NInputNumber
            v-model:value="shot.duration_sec"
            :min="1"
            :max="10"
            :step="0.5"
            size="small"
            style="width: 100%"
            :disabled="!!disabled"
          />
          <span class="hint-line text-secondary">秒</span>
        </label>
      </div>
    </section>
"""
src = src[:old_dur.start()] + new_dur + src[old_dur.end():]

# ---------- 3) 三个 section → 一个「声音」卡片 ----------
def section_by_label(text):
    """按 block-label 文本定位 <section class="block">...</section>"""
    i = src.index(text)
    s = src.rindex('    <section class="block">', 0, i)
    e = src.index('    </section>\n', i) + len('    </section>\n')
    return s, e

s_bind, e_bind = section_by_label('角色音色绑定（按说话人自动关联）')
s_music, e_music = section_by_label('配乐时间轴（可多段：起止 / 强弱 / 淡入淡出）')
s_narr, e_narr = section_by_label('旁白 / 台词（逐镜时间轴：可拖拽定位、一镜多段）')
must(s_bind < e_bind <= s_music < e_music <= s_narr < e_narr, '三个 section 顺序不符合预期')

bind_block = src[s_bind:e_bind]
music_block = src[s_music:e_music]
narr_block = src[s_narr:e_narr]


def strip_section_wrapper(block, label_line_keep):
    """去掉 <section class="block"> 外壳与标题行，留下内部内容（缩进降一级）"""
    b = block
    b = b[b.index('\n') + 1:]                      # 去掉 <section ...>
    b = b[:b.rindex('    </section>\n')]           # 去掉 </section>
    b = b.replace('      <p class="block-label font-mono">' + label_line_keep, '')
    # 把紧跟标题块删掉（标题可能跨行）
    return b


bind_inner = bind_block
bind_inner = bind_inner[bind_inner.index('\n') + 1:]
bind_inner = bind_inner[:bind_inner.rindex('    </section>\n')]
bind_inner = re.sub(r'^      <p class="block-label font-mono">.*?\n', '', bind_inner, count=1, flags=re.S)
bind_inner = re.sub(r'^      <VoiceBindingsTable', '        <VoiceBindingsTable', bind_inner, flags=re.M)
bind_inner = re.sub(r'^        :', '          :', bind_inner, flags=re.M)
bind_inner = re.sub(r'^      />', '        />', bind_inner, flags=re.M)

music_inner = music_block
music_inner = music_inner[music_inner.index('\n') + 1:]
music_inner = music_inner[:music_inner.rindex('    </section>\n')]
# 标题两行（跨行标签）
music_inner = music_inner.replace('      <p class="block-label font-mono">\n        配乐时间轴（可多段：起止 / 强弱 / 淡入淡出）\n      </p>\n', '')

narr_inner = narr_block
narr_inner = narr_inner[narr_inner.index('\n') + 1:]
narr_inner = narr_inner[:narr_inner.rindex('    </section>\n')]
narr_inner = narr_inner.replace('      <p class="block-label font-mono">旁白 / 台词（逐镜时间轴：可拖拽定位、一镜多段）</p>\n', '')

# 音频块里的片段：音色下拉 / 克隆入口 / 音色库列表 / 试听配乐按钮与说明
voice_select = re.search(r'        <label class="row">\n          <span class="key">配音音色.*?\n        </label>\n', audio_block, re.S)
must(voice_select, '找不到配音音色下拉')
vc_entry = re.search(r'        <div class="vc-entry">.*?\n        </div>\n', audio_block, re.S)
must(vc_entry, '找不到克隆音色入口')
vc_lib = re.search(r'        <!-- P9 音色库：.*?\n        </div>\n', audio_block, re.S)
must(vc_lib, '找不到音色库列表')
bgm_mood = re.search(r'        <label class="row">\n          <span class="key">BGM 情绪.*?\n        </label>\n', audio_block, re.S)
must(bgm_mood, '找不到 BGM 情绪下拉')
preview_bar = re.search(r'        <!-- P8：试听播放条.*?\n        </div>\n', audio_block, re.S)
must(preview_bar, '找不到试听播放条')

new_card = """    <!-- P12：声音相关全部收进一张卡片，顺序=音色 → 绑定 → 配音 → 配乐 -->
    <section class="block audio-card" data-testid="audio-card">
      <p class="block-label font-mono">声音（音色 / 角色音色绑定 / 配音 / 配乐）</p>

      <!-- 试听播放条固定在卡片顶部：点哪个试听都在这儿出现 -->
""" + "  " + preview_bar.group(0) + """
      <div class="sub-block">
        <p class="sub-label">① 配音音色</p>
        <div class="voice-row">
""" + voice_select.group(0).replace('        <label class="row">\n', '').replace('        </label>\n', '') + """          <NButton
            size="tiny"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-voice"
            title="用当前音色念一句试听"
            @click="emit('previewVoice', undefined)"
          >
            音色试听
          </NButton>
          <NButton
            size="tiny"
            quaternary
            :disabled="!!disabled || !selectedPreset"
            :data-testid="`preset-rerecord-selected`"
            title="重新录一段替换当前克隆音色"
            @click="rerecordSelected"
          >
            重录
          </NButton>
          <NButton
            size="tiny"
            quaternary
            :disabled="!!disabled || !selectedPreset"
            @click="renameSelected"
          >
            改名
          </NButton>
          <NButton
            size="tiny"
            quaternary
            type="error"
            :disabled="!!disabled || !selectedPreset"
            :data-testid="`preset-delete-selected`"
            @click="deleteSelected"
          >
            删除
          </NButton>
        </div>
        <p v-if="!selectedPreset" class="hint-line text-secondary">
          上面选一个「克隆」音色后，重录 / 改名 / 删除才可用
        </p>
""" + vc_entry.group(0) + vc_lib.group(0) + """      </div>

      <div class="sub-block">
        <p class="sub-label">② 角色音色绑定（按说话人自动关联）</p>
""" + bind_inner + """      </div>

      <div class="sub-block">
        <p class="sub-label">③ 配音（逐镜旁白 / 台词，可拖拽定位、一镜多段）</p>
""" + narr_inner + """      </div>

      <div class="sub-block">
        <p class="sub-label">④ 配乐（多段：起止 / 强弱 / 淡入淡出）</p>
""" + bgm_mood.group(0) + """        <div class="row">
          <span class="key">配乐试听</span>
          <NButton
            size="tiny"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-bgm"
            title="按当前情绪生成一条试听"
            @click="emit('previewBgm')"
          >
            试听配乐
          </NButton>
          <span class="text-secondary" style="font-size: 12px">
            换情绪后点一次即重生成；正式生成在下方任务区「生成配乐」
          </span>
        </div>
""" + music_inner + """      </div>
    </section>
"""

# 用新卡片替换「绑定」那一段起始位置到「旁白」section 结束
rep_start = s_bind
rep_end = e_narr
# 但 bind/music/narr 的内容已被引用，它们位于该区间内 → 直接整段替换
src = src[:rep_start] + new_card + src[rep_end:]

# ---------- 4) 样式 ----------
src = src.replace(
    """.vc-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: -2px 0 8px;
}""",
    """.vc-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0;
}
/* P12：镜头时长自适应网格（桌面多列，手机 1~2 列） */
.dur-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.dur-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
/* P12：声音卡片的分块 */
.sub-block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed rgba(140, 160, 190, 0.18);
}
.sub-block:first-of-type {
  border-top: none;
  padding-top: 0;
}
.sub-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  opacity: 0.9;
}
.voice-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.voice-row .n-select {
  flex: 1 1 200px;
  min-width: 160px;
}
/* P12：手机端适配 */
@media (max-width: 640px) {
  .dur-grid {
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  }
  .voice-row > * {
    flex: 1 1 auto;
  }
}""",
)

must('<section class="block audio-card"' in src, '新卡片未写入')
must('音色试听' in src, '重命名后的按钮未写入')
must(src.count('<span class="key">配音音色') == 1, '模板里配音音色标签应只剩一处（实际 %d）' % src.count('<span class="key">配音音色'))
must('试听配音' not in src, '旧按钮名应已消失')
must('角色音色绑定（按说话人自动关联）' in src, '绑定子块标题保留')
must(len(src) > 3000, '文件过短，可能被误删')

open(P, "w", encoding="utf-8", newline="\n").write(src)
print("OK: 模板重排完成（原 %d 字节 → 新 %d 字节）" % (len(orig), len(src)))
