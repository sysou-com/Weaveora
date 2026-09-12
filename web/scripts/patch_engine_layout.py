#!/usr/bin/env python3
"""图片云 API 排版优化：去长提示/去上限输入、按钮改名「解析示例参数」、行距放宽。幂等。"""
import io

p = "web/src/views/EngineSettingsView.vue"
s = io.open(p, encoding="utf-8").read()
if "网关（如火山方舟）只提供模型列表" not in s:
    print("already cleaned")
    raise SystemExit(0)

# 1) 删掉那段长提示 + 参考图上限行，改为「解析示例参数」按钮紧贴示例框
old1 = '''          <p class="gw-text">
            网关（如火山方舟）只提供模型列表（模态 / 任务类型），<b>没有逐参数规范</b>，所以无法自动获取参数。
            粘一段<b>示例请求</b>（curl 或 JSON body）→ 自动识别参考图字段、尺寸字段等参数名。
          </p>'''
old2 = '''          <div class="gw-row">
            <span class="gw-label">参考图上限（张）</span>
            <NInputNumber v-model:value="gatewayRefsMax" :min="0" :max="50" size="small"
                          style="width: 130px" placeholder="如 14" />
            <span class="gw-note text-secondary">超出会被自动裁剪并告警；0/空 = 用内置默认</span>
          </div>
'''
old3 = '''          <div class="gw-row">
            <NButton size="small" type="primary" :loading="saving" data-testid="btn-parse-sample-apply"
                     @click="save">解析参数并保存</NButton>
            <span class="gw-note text-secondary">
              解析后可在下方直接改参数值；生成时会**按保存的参数模板填充**，接口未传的字段以模板为准
            </span>
          </div>
'''
assert s.count(old1) == 1 and s.count(old2) == 1 and s.count(old3) == 1
s = s.replace(old1, "", 1)
s = s.replace(old2, "", 1)
s = s.replace(old3, '''          <div class="gw-row">
            <NButton size="small" type="primary" :loading="saving" data-testid="btn-parse-sample-apply"
                     @click="save">解析示例参数</NButton>
          </div>
''', 1)

# 2) 行距/间距放宽
s = s.replace(".gw-text {", ".gw-text {\n  line-height: 1.9;", 1) if ".gw-text {" in s else s
s = s.replace(".gw-hint {", """.gw-hint {
  line-height: 1.9;
  padding: 10px 12px;
  margin-bottom: 12px;""", 1)
s = s.replace(".gw-row {", """.gw-row {
  line-height: 1.85;
  margin: 8px 0;""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("view cleaned")

# 3) 后端：网关参考图上限默认 15（不再让用户配）
p = "api/src/main/java/studio/weaveora/engine/EngineSettingsService.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''                        "refsMax", s.gatewayRefsMax() == null ? 0 : s.gatewayRefsMax()),''',
              '''                        // 参考图上限：默认 15 张（超出 worker 会裁剪并告警 —— 防手滑烧 API 费用）
                        "refsMax", s.gatewayRefsMax() == null || s.gatewayRefsMax() <= 0
                                ? 15 : s.gatewayRefsMax()),''', 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("backend default 15")

# 4) worker：网关/方舟兜底上限 15
p = "worker/cloud_image.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace('ARK_MAX_REFS = int(os.environ.get("WEAVEORA_ARK_MAX_REFS", "10"))',
              'ARK_MAX_REFS = int(os.environ.get("WEAVEORA_ARK_MAX_REFS", "15"))', 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("worker default 15")

# 5) 前端提示：网关也按 15 兜底（引用图超限提醒用户）
p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''  const map = (s?.imageModelSchema?.mapping ?? {}) as Record<string, unknown>
  return {
    name: s?.imageCloudModel ?? '',
    max: Number(map.refsMax ?? 0) || 0,
    isArray: map.refsIsArray === true,
  }''', '''  const map = (s?.imageModelSchema?.mapping ?? {}) as Record<string, unknown>
  // 网关通道（方舟等）没有 schema → 用后端默认上限 15（超出会被裁剪，提示用户避免白烧费用）
  const gateway = !!(s?.imageCloudBaseUrl ?? '').trim()
  const max = Number(map.refsMax ?? 0) || (gateway ? Number(s?.gatewayRefsMax ?? 0) || 15 : 0)
  return { name: s?.imageCloudModel ?? '', max, isArray: map.refsIsArray === true }''', 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("frontend hint fallback 15")
