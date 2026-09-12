#!/usr/bin/env python3
"""P13：主体锚定（定妆图优先）+ 定妆图任务类型。幂等。"""
import io

J = "api/src/main/java/studio/weaveora/job/JobService.java"
s = io.open(J, encoding="utf-8").read()

# ---------- 1) resolveRefs：改成按「主体」取锚定资产（定妆图优先） ----------
old = '''        String text = (shot == null)
                ? plan.path("positive_prompt").asText("") + " " + plan.path("prompt_zh").asText("")
                : shot.path("action").asText("") + " " + shot.path("zh").asText("")
                  + " " + shot.path("positive_prompt").asText("");
        RefCtx fromPlan = bindFrom(plan == null ? null : plan.get("referenceAssets"), text, workspaceId);
        if (fromPlan != null) return fromPlan;'''
new = '''        String text = (shot == null)
                ? plan.path("positive_prompt").asText("") + " " + plan.path("prompt_zh").asText("")
                : shot.path("action").asText("") + " " + shot.path("zh").asText("")
                  + " " + shot.path("positive_prompt").asText("");
        // P13：按「剧情主体」取锚定资产 —— **定妆图优先**（一致性靠它），没有定妆图才退回勾选的素材图
        RefCtx fromSubjects = bindFromSubjects(plan, text, workspaceId);
        if (fromSubjects != null) return fromSubjects;
        RefCtx fromPlan = bindFrom(plan == null ? null : plan.get("referenceAssets"), text, workspaceId);
        if (fromPlan != null) return fromPlan;'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

# ---------- 2) 新增 bindFromSubjects ----------
anchor = "    /** brief.constraints.referenceAssets（新流程：未出方案前就标注的主体绑定）。取不到/无则 null。 */"
assert s.count(anchor) == 1
block = '''    /**
     * P13：按剧情主体绑定锚定图。
     *
     * <p>规则：
     * <ol>
     *   <li>只取 {@code enabled}（勾选参与）的主体；镜文案命中名/别名者优先，一个都没命中则带回全部（避免空锚定）。</li>
     *   <li>每个主体取「**定妆图** → 勾选的素材图」中的第一张；两者都没有则跳过。</li>
     *   <li>顺序：先「镜文案里出现且为主主体」的，再按方案里的声明顺序 —— 保住「第 i 张图 = 哪个主体」。</li>
     * </ol>
     */
    private RefCtx bindFromSubjects(JsonNode plan, String text, UUID workspaceId) {
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> subjects =
                studio.weaveora.director.plan.PlanSubjects.parse(plan);
        if (subjects.isEmpty()) {
            return null;
        }
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> enabled = subjects.stream()
                .filter(studio.weaveora.director.plan.PlanSubjects.Subject::enabled)
                .filter(s -> s.anchorAssetId() != null)
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        String t = text == null ? "" : text;
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> picked = enabled.stream()
                .filter(s -> studio.weaveora.director.plan.PlanSubjects.matches(t, s))
                .toList();
        if (picked.isEmpty()) {
            picked = enabled;
            log.info("refs: 镜文本未命中任何主体，回退为全部 {} 个主体（{}）",
                    picked.size(), picked.stream().map(studio.weaveora.director.plan.PlanSubjects.Subject::name).toList());
        }
        java.util.List<UUID> ids = new ArrayList<>();
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : picked) {
            try {
                ids.add(UUID.fromString(sub.anchorAssetId()));
            } catch (IllegalArgumentException ignored) {
                // 资产 id 非法（手工改过方案）→ 跳过该主体
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        java.util.Map<String, studio.weaveora.asset.domain.Asset> byId = new java.util.HashMap<>();
        for (studio.weaveora.asset.domain.Asset a : assetRepo.findByIdInAndWorkspaceId(ids, workspaceId)) {
            byId.put(a.id().toString(), a);
        }
        java.util.List<String> okIds = new ArrayList<>();
        java.util.List<String> keys = new ArrayList<>();
        java.util.List<String> subjNames = new ArrayList<>();
        java.util.List<String> regions = new ArrayList<>();
        StringBuilder mapping = new StringBuilder();
        String primary = "";
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : picked) {
            studio.weaveora.asset.domain.Asset a = byId.get(sub.anchorAssetId());
            if (a == null) {
                continue;
            }
            boolean portrait = sub.hasPortrait() && sub.portraitAssetId().equals(a.id().toString());
            subjNames.add(sub.name());
            keys.add(a.storageKey());
            okIds.add(a.id().toString());
            regions.add(null);
            if (mapping.length() > 0) {
                mapping.append("; ");
            }
            mapping.append(keys.size()).append(") ").append(sub.name())
                    .append(portrait ? "(定妆图)" : "(素材图)");
            if (primary.isEmpty() && studio.weaveora.director.plan.PlanSubjects.nameMatches(t, sub.name())) {
                primary = sub.name();
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        if (primary.isEmpty()) {
            primary = subjNames.get(0);
        }
        String anchor = "\\nReference images in order: " + mapping
                + ". Each subject MUST strictly match its own reference image (face / hair / costume / shape);"
                + " keep subjects distinct and never blend or swap their identities.";
        log.info("refs: 本镜锚定 {} 张（{}）primary={}", keys.size(), mapping, primary);
        return new RefCtx(okIds, keys, subjNames, regions, anchor, primary);
    }

'''
s = s.replace(anchor, block + anchor, 1)

# ---------- 3) 定妆图任务（kind=portrait） ----------
old_kind = '''        if (req.kind() == null || !List.of("still", "clip", "voice", "bgm").contains(req.kind())) {'''
new_kind = '''        if (req.kind() == null || !List.of("still", "clip", "voice", "bgm", "portrait").contains(req.kind())) {'''
assert s.count(old_kind) == 1, s.count(old_kind)
s = s.replace(old_kind, new_kind, 1)

old_audio = '''        boolean audioKind = "voice".equals(req.kind()) || "bgm".equals(req.kind());'''
new_audio = '''        if ("portrait".equals(req.kind())) {
            return createPortraitJob(workspaceId, projectId, req, plan, revisionNo, userId);
        }
        boolean audioKind = "voice".equals(req.kind()) || "bgm".equals(req.kind());'''
assert s.count(old_audio) == 1
s = s.replace(old_audio, new_audio, 1)

# 在 emptyShotReason 之前插入 createPortraitJob
anchor2 = "    /** P12：镜头被过滤空时的准确原因"
assert s.count(anchor2) == 1
portrait = '''    /**
     * P13 定妆图（subject portrait）：用该主体勾选的素材图/上一版定妆图做输入，生成一张「标准角色设定图」。
     *
     * <p>这张图之后会作为该主体在所有分镜里的**唯一锚定图** —— 直接喂随手拍的用户图，
     * 风格/构图不可控，一致性会飘；先定妆再锚定才稳定。
     */
    private List<GenerationJob> createPortraitJob(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                                  JsonNode plan, int revisionNo, UUID userId) {
        String subject = req.subject() == null ? "" : req.subject().trim();
        if (subject.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "生成定妆图需要指定主体名（subject）");
        }
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        studio.weaveora.director.plan.PlanSubjects.Subject sub =
                studio.weaveora.director.plan.PlanSubjects.parse(plan).stream()
                        .filter(s -> subject.equals(s.name()))
                        .findFirst()
                        .orElseThrow(() -> new BizException(ErrorCode.VALIDATION,
                                "方案里没有主体「" + subject + "」（先在参考图卡片里「一键生成主体」或手动添加）"));
        // 输入图：本主体的定妆图（换一版）优先，否则勾选的素材图
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Ref> refs = sub.checkedRefs();
        java.util.List<UUID> ids = new ArrayList<>();
        if (sub.hasPortrait()) {
            try {
                ids.add(UUID.fromString(sub.portraitAssetId()));
            } catch (IllegalArgumentException ignored) {
                // 忽略非法 id
            }
        }
        for (studio.weaveora.director.plan.PlanSubjects.Ref r : refs) {
            try {
                ids.add(UUID.fromString(r.assetId()));
            } catch (IllegalArgumentException ignored) {
                // 忽略
            }
        }
        java.util.List<String> keys = new ArrayList<>();
        for (studio.weaveora.asset.domain.Asset a : assetRepo.findByIdInAndWorkspaceId(ids, workspaceId)) {
            keys.add(a.storageKey());
        }
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kind", "portrait");
        payload.put("mode", "video");
        payload.put("revisionId", req.revisionId().toString());
        payload.put("revision_no", revisionNo);
        payload.put("subject", subject);
        payload.put("portrait_version", sub.portraitVersion() + 1);
        payload.put("positive_prompt", studio.weaveora.director.SubjectPrompts.portraitPrompt(sub.name(), sub.kind(), keys.size()));
        payload.put("negative_prompt", "text, watermark, logo, multiple people, deformed face, extra limbs, lowres");
        payload.put("aspect_ratio", project.aspectRatio());
        int[] dd = dimsFor(project.aspectRatio());
        payload.set("params", mapper().createObjectNode().put("width", dd[0]).put("height", dd[1]));
        payload.put("seed", randomSeed());
        ObjectNode keysNode = payload.putArray("referenceKeys");
        keys.forEach(keysNode::add);
        ObjectNode subjNode = payload.putArray("referenceSubjects");
        keys.forEach(k -> subjNode.add(subject));
        payload.put("primarySubject", subject);
        stampRevisionMeta(payload, revisionNo, payload.path("positive_prompt").asText(""));
        String engine = engineSettings.resolveEngine(userId, "still");
        GenerationJob job = createOne(workspaceId, projectId, req.revisionId(), null, PRESET_STILL,
                "portrait", payload, userId, engine);
        log.info("portrait job created project={} subject={} refs={}", projectId, subject, keys.size());
        return List.of(job);
    }

'''
s = s.replace(anchor2, portrait + anchor2, 1)
io.open(J, "w", encoding="utf-8", newline="\n").write(s)
print("JobService patched")

# ---------- 4) CreateJobRequest 增 subject，允许 portrait ----------
P = "api/src/main/java/studio/weaveora/job/api/CreateJobRequest.java"
s = io.open(P, encoding="utf-8").read()
if "String subject" not in s:
    s = s.replace('''        Boolean includeLocked              // P12：true=连已封版镜一起生成（默认 false）''',
                  '''        Boolean includeLocked,             // P12：true=连已封版镜一起生成（默认 false）
        String subject                     // P13：kind=portrait 时指定剧情主体名''', 1)
    s = s.replace('''        String kind,          // still | clip | voice | bgm''',
                  '''        String kind,          // still | clip | voice | bgm | portrait（P13 定妆图）''', 1)
    io.open(P, "w", encoding="utf-8", newline="\n").write(s)
    print("CreateJobRequest patched")
else:
    print("CreateJobRequest already patched")
