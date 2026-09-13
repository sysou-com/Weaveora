#!/usr/bin/env python3
"""P13-lipsync ①：后端接入 kind=lipsync（白名单 + 任务构造）；②渲染优先用对口型产物。
③导演 Prompt 加「对话镜头避开正脸 / lip_sync 标记 / speaking 提示」。幂等。"""
import io

# ---------- ① JobService：kind 白名单 + lipsync 任务 ----------
p = "api/src/main/java/studio/weaveora/job/JobService.java"
s = io.open(p, encoding="utf-8").read()
if '"lipsync"' not in s:
    s = s.replace(
        '!List.of("still", "clip", "voice", "bgm", "portrait").contains(req.kind())',
        '!List.of("still", "clip", "voice", "bgm", "portrait", "lipsync").contains(req.kind())', 1)
    s = s.replace(
        'String kind = List.of("clip", "still", "voice", "bgm", "portrait").contains(job.kind()) ? job.kind() : "still";',
        'String kind = List.of("clip", "still", "voice", "bgm", "portrait", "lipsync").contains(job.kind()) ? job.kind() : "still";', 1)

    anchor = """        if ("portrait".equals(req.kind())) {
            return createPortraitJob(workspaceId, projectId, req, plan, revisionNo, userId)
                    .stream().map(this::toView).toList();
        }"""
    add = anchor + """
        if ("lipsync".equals(req.kind())) {
            return createLipsyncJobs(workspaceId, projectId, req, plan, revisionNo, userId)
                    .stream().map(this::toView).toList();
        }"""
    assert s.count(anchor) == 1
    s = s.replace(anchor, add, 1)

    # createLipsyncJobs：clip 产物（画面）+ 该镜配音（音频）→ 对口型任务
    anchor2 = """    /**
     * P13 定妆图（subject portrait）"""
    add2 = """    /**
     * P13 对口型（lipsync）：把该镜的 **画面产物** 与 **该镜配音** 一起交给口型模型，
     * 输出「嘴型与台词对齐」的片段。
     *
     * <p>为什么必须单独一条任务：图生视频模型（Wan i2v 等）没有音频通道，
     * 出来的画面不可能对口型；口型要靠音频驱动的后处理（本地 LatentSync / 云端 lipsync）。
     *
     * <p>只对**有台词的镜**有意义；建议只在对话 + 特写/近景镜头上跑（远景/背影白花钱）。
     * 时长对齐要求：音频与画面基本等长 → 对话镜建议用 audio_first 模式（镜长=配音长）。
     */
    private List<GenerationJob> createLipsyncJobs(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                                 JsonNode plan, int revisionNo, UUID userId) {
        List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(),
                "clip", req.shotNos(), Boolean.TRUE.equals(req.includeLocked()));
        if (shotIds.isEmpty()) {
            throw new BizException(ErrorCode.SHOT_NOT_APPROVED, emptyShotReason(workspaceId, projectId, req));
        }
        String engineRoute = engineSettings.resolveEngine(userId, "clip");
        List<GenerationJob> created = new ArrayList<>();
        for (UUID shotId : shotIds) {
            JsonNode shot = shotOf(plan, shotId);
            if (shot == null) {
                continue;
            }
            int shotNo = shot.path("shot_no").asInt();
            // 画面：该镜最新的 motion 片段（没有 motion 就用关键帧静帧，口型模型能处理静帧）
            Asset clip = pickNewestAsset(projectId, workspaceId, shotNo, "clip");
            Asset still = clip != null ? clip : pickNewestAsset(projectId, workspaceId, shotNo, "still");
            if (still == null) {
                continue;
            }
            // 音频：该镜全部配音段（按 line_index 升序，取每段最新）
            List<Asset> voices = newestVoicePerLine(projectId, workspaceId, shotNo);
            if (voices.isEmpty()) {
                continue;   // 没配音就没有可对的口型
            }
            ObjectNode payload = mapper().createObjectNode();
            payload.put("kind", "lipsync");
            payload.put("mode", "video");
            payload.put("revisionId", req.revisionId().toString());
            payload.put("shotId", shotId.toString());
            payload.put("shot_no", shotNo);
            payload.put("videoKey", still.storageKey());
            payload.put("videoIsStill", clip == null);
            payload.put("isStill", clip == null);
            ArrayNode vk = payload.putArray("voiceKeys");
            for (Asset a : voices) {
                vk.add(a.storageKey());
            }
            payload.put("voiceCount", voices.size());
            payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
            payload.put("lipSync", shot.path("lip_sync").asBoolean(true));
            created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                    PRESET_CLIP, "lipsync", payload, userId, engineRoute));
        }
        if (created.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION,
                    "没有可对口型的镜头：需要该镜已有 motion/关键帧**且**已生成配音");
        }
        return created;
    }

    /** 该项目/镜号下最新的一条某类产物。 */
    private Asset pickNewestAsset(UUID projectId, UUID workspaceId, int shotNo, String kind) {
        List<Asset> list = assetRepo
                .findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(projectId, workspaceId, shotNo, kind);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 该镜每个 line_index 的最新配音产物（升序），用于对口型。 */
    private List<Asset> newestVoicePerLine(UUID projectId, UUID workspaceId, int shotNo) {
        List<Asset> all = assetRepo
                .findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(projectId, workspaceId, shotNo, "voice");
        Map<Integer, Asset> newest = new java.util.TreeMap<>();
        for (Asset a : all) {
            JsonNode snap = a.promptSnapshot();
            int li = (snap != null && snap.hasNonNull("line_index")) ? snap.path("line_index").asInt(0) : 0;
            newest.putIfAbsent(li, a);
        }
        return new ArrayList<>(newest.values());
    }

    /**
     * P13 定妆图（subject portrait）"""
    assert s.count(anchor2) == 1
    s = s.replace(anchor2, add2, 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("JobService: kind=lipsync + createLipsyncJobs")
else:
    print("JobService: already patched")

# ---------- ② 渲染：优先用对口型产物 ----------
p2 = "api/src/main/java/studio/weaveora/export/ConcatService.java"
t = io.open(p2, encoding="utf-8").read()
old = """    private Asset pickClipOrStill(UUID workspaceId, UUID projectId, UUID shotId, int shotNo) {
        // P6：优先 (project, shot_no)；退 shot_id"""
new = """    private Asset pickClipOrStill(UUID workspaceId, UUID projectId, UUID shotId, int shotNo) {
        // P13：对口型产物优先 —— 有 lipsync 就用它（嘴型已与台词对齐），否则用普通 motion/关键帧
        List<Asset> lips = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "lipsync");
        if (lips.isEmpty() && shotId != null) {
            lips = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "lipsync");
        }
        if (!lips.isEmpty()) {
            return lips.get(0);
        }
        // P6：优先 (project, shot_no)；退 shot_id"""
assert t.count(old) == 1
t = t.replace(old, new, 1)
io.open(p2, "w", encoding="utf-8", newline="\n").write(t)
print("ConcatService: lipsync asset preferred")

# ---------- ③ 导演 Prompt：对话镜头禁正脸 + lip_sync 标记 ----------
p3 = "api/src/main/java/studio/weaveora/director/DirectorService.java"
d = io.open(p3, encoding="utf-8").read()
old3 = '"video", "你是电影摄影指导+分镜师。输出且只输出 JSON（视频导演方案：mode/title/logline/duration_sec/aspect_ratio/script/shots/audio/edit_plan；镜头时长总和==目标时长，每镜 positive_prompt 20–1200）。"'
new3 = '"video", "你是电影摄影指导+分镜师。输出且只输出 JSON（视频导演方案：mode/title/logline/duration_sec/aspect_ratio/script/shots/audio/edit_plan；镜头时长总和==目标时长，每镜 positive_prompt 20–1200）。关键规则：① 有台词的镜头尽量不用正脸大特写，改用过肩/侧脸/听者反应/手部或环境特写（图生视频模型无法对口型，正脸会让嘴型穿帮）；② 若镜头必须出现正脸说话，positive_prompt 里加 speaking、mouth moving，并置 shots[].lip_sync=true；③ 旁白镜头 lip_sync=false。"'
assert d.count(old3) == 1
d = d.replace(old3, new3, 1)
io.open(p3, "w", encoding="utf-8", newline="\n").write(d)
print("DirectorService: lip-sync aware video prompt")
