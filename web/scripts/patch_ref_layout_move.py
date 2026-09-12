#!/usr/bin/env python3
"""布局：参考图 / 位置预览 并排等高；把「已选主体」与红色告警挪到位置预览的「拖动色块移动…」提示下方。幂等。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if 'class="pos-subjects"' in s:
    print("already patched")
    raise SystemExit(0)

# 1) 从参考图卡片里摘出「已选主体」块
start = s.index('            <!-- P13：勾选的主体（显示定妆照，名字只读，只编区域；取消勾选即从显示中移除） -->')
end = s.index('            <p v-if="refSelected.length" class="ref-count font-mono"') if '<p v-if="refSelected.length" class="ref-count font-mono"' in s[start:] else s.index('            <p v-else class="ref-hint text-secondary">')
# 兼容：块后面紧跟的可能是 ref-count 或 ref-hint
block_end = s.index('            </div>\n', s.index('</div>\n              </div>\n            </div>', start)) if False else None
# 用更稳的方式：块以 '<div v-if="enabledSubjects.length"' 开头，取到与之匹配的结束
i = s.index('<div v-if="enabledSubjects.length" class="ref-subjects" data-testid="subject-ref-block">')
depth = 0
j = i
while True:
    nxt_open = s.find('<div', j + 1)
    nxt_close = s.find('</div>', j + 1)
    if nxt_open != -1 and nxt_open < nxt_close:
        depth += 1
        j = nxt_open
    else:
        if depth == 0:
            j = nxt_close + len('</div>')
            break
        depth -= 1
        j = nxt_close
subjects_block = s[i:j]
s = s[:i] + s[j:]
print("已摘出『已选主体』块：%d 字符" % len(subjects_block))

# 2) 摘出 red 告警（ref-model-limit / cameraIntentWithRefs）与计数
meta_i = s.index('          <!-- 计数与提示放到「位置预览」描述下方 -->')
meta_j = s.index('</div>', s.index('<p v-if="cameraIntentWithRefs"', meta_i))
meta_j = s.index('</div>', meta_j + 6) + len('</div>')
meta_block = s[meta_i:meta_j]
s = s[:meta_i] + s[meta_j:]
print("已摘出「计数与提示」块：%d 字符" % len(meta_block))

# 3) 插到位置预览的「拖动色块移动…」提示下方
anchor = '''            <p class="ref-hint text-secondary">
              拖动色块移动、右下角拖动缩放；也可在上方「区域%」精确填写（x/y=左上角，w/h=宽高，0–100）。填了区域后，GPU(Comfy) 会按区域分别注入参考图（彻底解耦多角色）；云模型无遮罩能力，会把方位写进提示词。
            </p>'''
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '\n' + subjects_block.replace('class="ref-subjects"', 'class="ref-subjects pos-subjects"') + '\n' + meta_block, 1)

# 4) 并排 + 等高
s = s.replace(".ref-row { display: grid; grid-template-columns: minmax(0, 360px) minmax(0, 1fr); gap: 12px; margin-bottom: 16px; }",
              """.ref-row {
  display: grid;
  grid-template-columns: minmax(0, 360px) minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 16px;
  /* P13：两卡并排且**始终等高**（以前左卡高右卡矮，页面看着不齐） */
  align-items: stretch;
}
.ref-row > .refs-panel,
.ref-row > .pos-panel {
  height: 100%;
}""", 1)
s = s.replace(""".pos-panel {
  display: flex; flex-direction: column; gap: 8px; min-width: 0;""",
              """.pos-panel {
  display: flex; flex-direction: column; gap: 8px; min-width: 0;
  /* 等高：内容多时自己滚，不把另一张卡撑变形 */
  overflow: auto;""", 1)
s = s.replace(".refs-panel { width: auto; }", ".refs-panel { width: auto; overflow: auto; }", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
