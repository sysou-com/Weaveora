#!/usr/bin/env python3
"""P12 模型库：Service/Response/Controller 补丁（幂等）。"""
import io

BASE = "api/src/main/java/studio/weaveora/engine/"

# ---------------- Service ----------------
p = BASE + "EngineSettingsService.java"
s = io.open(p, encoding="utf-8").read()
if "listPresets" not in s:
    s = s.replace(
        "                s.gatewaySample());",
        "                s.gatewaySample(), s.imageModelPresets(), s.videoModelPresets());", 1)

    anchor = "    /** 网关通道（OpenAI Images 兼容）？网关不是 Replicate，无法自动拉 schema。 */"
    assert s.count(anchor) == 1
    block = '''    // ---------------------------------------------------------------- P12 模型库

    /** 列出模型库（kind=image|video）。 */
    @Transactional(readOnly = true)
    public JsonNode listPresets(UUID userId, String kind) {
        UserEngineSettings s = load(userId);
        JsonNode arr = "video".equals(kind) ? s.videoModelPresets() : s.imageModelPresets();
        return arr == null || !arr.isArray() ? mapper.createArrayNode() : arr;
    }

    /**
     * 新增/更新一个模型条目，并**顺手刷新它的参数说明**。
     *
     * <p>为什么在这做：换模型/改参数后必须重新解析参数格式（Replicate 拉 schema；网关解析示例），
     * 否则界面展示的会是上一个模型的说明（实测踩过）。
     */
    @Transactional
    public EngineSettingsResponse upsertPreset(UUID userId, studio.weaveora.engine.api.ModelPresetRequest req) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(req.kind());
        String model = req.model() == null ? "" : req.model().trim();
        if (model.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "模型名不能为空");
        }
        String baseUrl = req.baseUrl() == null ? "" : req.baseUrl().trim();
        boolean apply = req.apply() != null && req.apply();
        if (apply) {
            if (video) {
                s.setVideoCloudModel(model);
            } else {
                s.setImageCloudBaseUrl(baseUrl);
                s.setImageCloudModel(model);
            }
        }
        if (!video) {
            if (req.gatewayRefsMax() != null) {
                s.setGatewayRefsMax(req.gatewayRefsMax() >= 0 ? req.gatewayRefsMax() : null);
            }
            if (req.gatewaySample() != null) {
                s.setGatewaySample(req.gatewaySample().isBlank() ? null : req.gatewaySample());
            }
        }
        if (req.params() != null) {
            if (video) {
                s.setVideoParams(schemaService.sanitizeParams(s.videoModelSchema(), req.params()));
            } else {
                s.setImageParams(schemaService.sanitizeParams(s.imageModelSchema(), req.params()));
            }
        }
        repo.save(s);
        refreshSchemas(userId, !video, video);
        UserEngineSettings fresh = repo.findByUserId(userId).orElse(s);
        this.current = fresh;
        upsertPresetInto(fresh, video, baseUrl, model, presetEntry(fresh, video, baseUrl, model));
        repo.save(fresh);
        return toResponse(userId);
    }

    /** 只刷新某个条目的参数说明（界面上的「刷新参数说明」）。 */
    @Transactional
    public EngineSettingsResponse refreshPreset(UUID userId, String kind, String baseUrl, String model) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(kind);
        if (baseUrl != null && !baseUrl.isBlank() && !video) {
            s.setImageCloudBaseUrl(baseUrl.trim());
        }
        if (model != null && !model.isBlank()) {
            if (video) {
                s.setVideoCloudModel(model.trim());
            } else {
                s.setImageCloudModel(model.trim());
            }
        }
        repo.save(s);
        refreshSchemas(userId, !video, video);
        UserEngineSettings fresh = repo.findByUserId(userId).orElse(s);
        this.current = fresh;
        upsertPresetInto(fresh, video, baseUrl, model, presetEntry(fresh, video, baseUrl, model));
        repo.save(fresh);
        return toResponse(userId);
    }

    /** 删除一个条目。 */
    @Transactional
    public EngineSettingsResponse deletePreset(UUID userId, String kind, String baseUrl, String model) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(kind);
        ArrayNode keep = mapper.createArrayNode();
        for (JsonNode e : arr(s, video)) {
            if (!sameKey(e, baseUrl, model)) {
                keep.add(e);
            }
        }
        if (video) {
            s.setVideoModelPresets(keep);
        } else {
            s.setImageModelPresets(keep);
        }
        repo.save(s);
        return toResponse(userId);
    }

    private JsonNode arr(UserEngineSettings s, boolean video) {
        JsonNode a = video ? s.videoModelPresets() : s.imageModelPresets();
        return a != null && a.isArray() ? a : mapper.createArrayNode();
    }

    private static boolean sameKey(JsonNode e, String baseUrl, String model) {
        return e.path("model").asText("").equals(model == null ? "" : model.trim())
                && e.path("baseUrl").asText("").equals(baseUrl == null ? "" : baseUrl.trim());
    }

    /** 组装一条库条目（含该条目对应的参数说明/参数）。 */
    private ObjectNode presetEntry(UserEngineSettings s, boolean video, String baseUrl, String model) {
        ObjectNode e = mapper.createObjectNode();
        e.put("baseUrl", baseUrl == null ? "" : baseUrl.trim());
        e.put("model", model == null ? "" : model.trim());
        e.set("params", video ? s.videoParams() : s.imageParams());
        e.set("schema", video ? s.videoModelSchema() : s.imageModelSchema());
        java.time.OffsetDateTime at = video ? s.videoModelSchemaAt() : s.imageModelSchemaAt();
        e.put("schemaAt", at == null ? "" : at.toString());
        String err = video ? s.videoModelSchemaError() : s.imageModelSchemaError();
        e.put("schemaError", err == null ? "" : err);
        if (!video) {
            e.put("gatewayRefsMax", s.gatewayRefsMax() == null ? 0 : s.gatewayRefsMax());
            e.put("gatewaySample", s.gatewaySample() == null ? "" : s.gatewaySample());
        }
        e.put("updatedAt", java.time.OffsetDateTime.now().toString());
        return e;
    }

    /** 按 (kind, baseUrl, model) 覆盖写入模型库。 */
    private void upsertPresetInto(UserEngineSettings s, boolean video, String baseUrl, String model,
                                  ObjectNode entry) {
        ArrayNode keep = mapper.createArrayNode();
        for (JsonNode e : arr(s, video)) {
            if (!sameKey(e, baseUrl, model)) {
                keep.add(e);
            }
        }
        keep.add(entry);
        if (video) {
            s.setVideoModelPresets(keep);
        } else {
            s.setImageModelPresets(keep);
        }
    }

'''
    s = s.replace(anchor, block + anchor, 1)
    s = s.replace("import studio.weaveora.engine.api.EngineSettingsRequest;",
                  "import com.fasterxml.jackson.databind.JsonNode;\n"
                  "import com.fasterxml.jackson.databind.node.ArrayNode;\n"
                  "import com.fasterxml.jackson.databind.node.ObjectNode;\n"
                  "import studio.weaveora.engine.api.EngineSettingsRequest;", 1)
    s = s.replace("import studio.weaveora.infra.crypto.AesGcm;",
                  "import studio.weaveora.infra.crypto.AesGcm;\n"
                  "import studio.weaveora.shared.api.BizException;\n"
                  "import studio.weaveora.shared.api.ErrorCode;", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("service ok")
else:
    print("service already patched")

# ---------------- Response ----------------
p = BASE + "api/EngineSettingsResponse.java"
s = io.open(p, encoding="utf-8").read()
if "imageModelPresets" not in s:
    s = s.replace("""        /** 网关通道：已保存的示例请求 */
        String gatewaySample
) {
}""", """        /** 网关通道：已保存的示例请求 */
        String gatewaySample,
        /** P12 模型库：已配置的模型条目（baseUrl/model/params/schema…） */
        com.fasterxml.jackson.databind.JsonNode imageModelPresets,
        com.fasterxml.jackson.databind.JsonNode videoModelPresets
) {
}""", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("response ok")
else:
    print("response already patched")

# ---------------- Controller ----------------
p = BASE + "api/EngineSettingsController.java"
s = io.open(p, encoding="utf-8").read()
if "upsertModel" not in s:
    s = s.replace("    private UUID uid(HttpServletRequest request) {", '''    /** P12 模型库：新增/更新条目（顺手刷新该模型的参数说明；apply=true 同时设为当前生效模型）。 */
    @org.springframework.web.bind.annotation.PostMapping("/models")
    public EngineSettingsResponse upsertModel(HttpServletRequest request,
                                              @Valid @RequestBody ModelPresetRequest body) {
        return service.upsertPreset(uid(request), body);
    }

    /** P12 模型库：刷新某条目的参数说明。 */
    @org.springframework.web.bind.annotation.PostMapping("/models/refresh")
    public EngineSettingsResponse refreshModel(HttpServletRequest request,
                                               @RequestParam("kind") String kind,
                                               @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                               @RequestParam("model") String model) {
        return service.refreshPreset(uid(request), kind, baseUrl, model);
    }

    /** P12 模型库：删除条目。 */
    @org.springframework.web.bind.annotation.PostMapping("/models/delete")
    public EngineSettingsResponse deleteModel(HttpServletRequest request,
                                              @RequestParam("kind") String kind,
                                              @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                              @RequestParam("model") String model) {
        return service.deletePreset(uid(request), kind, baseUrl, model);
    }

    private UUID uid(HttpServletRequest request) {''', 1)
    s = s.replace("import org.springframework.web.bind.annotation.GetMapping;",
                  "import org.springframework.web.bind.annotation.GetMapping;\n"
                  "import org.springframework.web.bind.annotation.RequestParam;", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("controller ok")
else:
    print("controller already patched")
