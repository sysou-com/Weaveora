#!/usr/bin/env python3
"""P13：运动帧数上限按「模型 schema」收口（Wan num_frames.max=121）+ source 说明。幂等。"""
import io

p = "api/src/main/java/studio/weaveora/engine/EngineSettingsService.java"
s = io.open(p, encoding="utf-8").read()
if "videoSchemaFramesMax" not in s:
    anchor = "    /** 只刷新某个条目的参数说明（界面上的「刷新参数说明」）。 */"
    add = '''    /**
     * 视频模型 schema 里「帧数」字段的上限（如 Wan 的 num_frames.max=121）。
     *
     * <p>「运动帧数」的可用上限必须按**模型**收口：本机 GPU 由显存决定，
     * 云模型由它自己声明的 max 决定（旧实现只用一个全局配置 300，比模型大 -> 送出去会被拒/被截）。
     */
    public Integer videoSchemaFramesMax() {
        UserEngineSettings s = current;
        if (s == null) {
            return null;
        }
        return framesMaxOf(s.videoModelSchema());
    }

    private static Integer framesMaxOf(com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode params = schema.path("params");
        if (!params.isArray()) {
            return null;
        }
        for (String cand : java.util.List.of("num_frames", "frames", "video_length", "length", "frame_count")) {
            for (com.fasterxml.jackson.databind.JsonNode pn : params) {
                if (cand.equals(pn.path("name").asText("")) && pn.path("max").isNumber()) {
                    return (int) Math.round(pn.path("max").asDouble());
                }
            }
        }
        return null;
    }

    /** 只刷新某个条目的参数说明（界面上的「刷新参数说明」）。 */'''
    assert s.count(anchor) == 1
    s = s.replace(anchor, add, 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("EngineSettingsService patched")
else:
    print("EngineSettingsService already patched")

p2 = "api/src/main/java/studio/weaveora/job/JobService.java"
t = io.open(p2, encoding="utf-8").read()
old = '''        int fps = Math.max(1, plan.path("edit_plan").path("fps").asInt(30));
        double capSec = plan.path("edit_plan").path("video_model_max_sec").asDouble(0);
        if (capSec > 0) {
            return Math.max(motionFramesMin, (int) Math.round(capSec * fps));
        }
        return Math.max(motionFramesMin, motionFramesMaxCloud);'''
new = '''        int fps = Math.max(1, plan.path("edit_plan").path("fps").asInt(30));
        // 1) 项目里显式设了「模型上限(s)」-> 用它（最准）
        double capSec = plan.path("edit_plan").path("video_model_max_sec").asDouble(0);
        if (capSec > 0) {
            return Math.max(motionFramesMin, (int) Math.round(capSec * fps));
        }
        // 2) 否则看**模型 schema** 里帧数字段的上限（如 Wan num_frames.max=121）
        Integer schemaMax = engineSettings.videoSchemaFramesMax();
        if (schemaMax != null && schemaMax > 0) {
            return Math.max(motionFramesMin, Math.min(schemaMax, motionFramesMaxCloud));
        }
        // 3) 都没有 -> 云配置兜底
        return Math.max(motionFramesMin, motionFramesMaxCloud);'''
if old in t:
    t = t.replace(old, new, 1)
    print("JobService motionFramesMaxFor patched")
else:
    print("JobService motionFramesMaxFor: already patched or anchor missing")

old2 = '''                "source", "cloud".equals(route)
                        ? (plan.path("edit_plan").path("video_model_max_sec").asDouble(0) > 0
                            ? "项目「模型上限」× fps" : "云配置 motion-frames-max-cloud")
                        : "本机 GPU 显存（motion-frames-max）")'''
new2 = '''                "source", !"cloud".equals(route)
                        ? "本机 GPU 显存（motion-frames-max）"
                        : (plan.path("edit_plan").path("video_model_max_sec").asDouble(0) > 0
                            ? "项目「模型上限」× fps"
                            : (hi < motionFramesMaxCloud ? "云模型 schema 的帧数上限" : "云配置 motion-frames-max-cloud")))'''
if old2 in t:
    t = t.replace(old2, new2, 1)
    print("JobService source patched")
else:
    print("JobService source: already patched or anchor missing")
io.open(p2, "w", encoding="utf-8", newline="\n").write(t)
