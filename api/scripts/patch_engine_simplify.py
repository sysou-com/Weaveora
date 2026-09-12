#!/usr/bin/env python3
"""P13 引擎配置简化：保存即入库（无需按钮）+ 网关无 schema 时参数不做收口。幂等。"""
import io

p = "api/src/main/java/studio/weaveora/engine/EngineSettingsService.java"
s = io.open(p, encoding="utf-8").read()

# 1) sanitizeParams：网关未解析出参数表时，保留用户填的参数（否则会被丢空）
old_san = '''        ObjectNode out = mapper.createObjectNode();
        if (schema == null || userParams == null || !userParams.isObject()) {
            return out;
        }
        JsonNode params = schema.path("params");'''
new_san = '''        ObjectNode out = mapper.createObjectNode();
        if (userParams == null || !userParams.isObject()) {
            return out;
        }
        // 网关未解析出参数表（没贴示例）时无法校验 —— 原样保留用户模板，作为调用时的默认值
        if (schema == null || !schema.path("params").isArray() || schema.path("params").isEmpty()) {
            return userParams.deepCopy();
        }
        JsonNode params = schema.path("params");'''
# 该方法在 ModelSchemaService 里，不在 Service
p2 = "api/src/main/java/studio/weaveora/engine/ModelSchemaService.java"
s2 = io.open(p2, encoding="utf-8").read()
assert s2.count(old_san) == 1, s2.count(old_san)
s2 = s2.replace(old_san, new_san, 1)
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("sanitizeParams ok")

# 2) update：保存配置时自动写入模型库（不再需要“保存到模型库”按钮）
old_up = '''        repo.save(s);
        // 换了模型 → 主动拉一次它的调用说明（失败不阻塞保存）
        if (imageModelChanged || videoModelChanged) {
            return refreshSchemas(userId, imageModelChanged, videoModelChanged);
        }
        return toResponse(userId);'''
new_up = '''        repo.save(s);
        // 换了模型 → 主动拉一次它的调用说明（失败不阻塞保存）
        if (imageModelChanged || videoModelChanged) {
            refreshSchemas(userId, imageModelChanged, videoModelChanged);
        }
        // P13：保存配置即入库（同 baseUrl+model 覆盖）—— 界面上不再需要「保存到模型库」按钮
        UserEngineSettings cur = repo.findByUserId(userId).orElse(s);
        this.current = cur;
        if (cur.imageCloudModel() != null && !cur.imageCloudModel().isBlank()) {
            upsertPresetInto(cur, false, cur.imageCloudBaseUrl(), cur.imageCloudModel(),
                    presetEntry(cur, false, cur.imageCloudBaseUrl(), cur.imageCloudModel()));
        }
        if (cur.videoCloudModel() != null && !cur.videoCloudModel().isBlank()) {
            upsertPresetInto(cur, true, null, cur.videoCloudModel(),
                    presetEntry(cur, true, null, cur.videoCloudModel()));
        }
        repo.save(cur);
        return toResponse(userId);'''
assert s.count(old_up) == 1, s.count(old_up)
s = s.replace(old_up, new_up, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("auto-preset on save ok")
