package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 离线 Stub 导演（未配置 weaveora.llm.* 时兜底，§10.1/§0-16 同步语义下也可演示全链路）。
 * 产出的方案必须通过 DirectorPlanValidator：时长总和==目标、每镜正向词 20–1200、数量 ≤ ceil(duration/1.5)。
 * 固定随机种子，便于 QA 复现。前端以 source=stub 提示“示例方案（未接 LLM）”。
 */
public class StubDirectorLlm implements DirectorLlm {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> SHOT_SIZES = List.of("wide", "medium", "close-up", "wide", "medium");
    private static final List<String> CAMERA_MOVES = List.of(
            "slow dolly in", "gentle tracking left", "static, subtle push", "slow pan right", "handheld drift");

    @Override
    public String generateJson(LlmRequest req) {
        ObjectNode plan = mapper.createObjectNode();
        String title = cleanTitle(req.projectTitle(), req.briefText());
        String logline = firstLine(req.briefText());
        plan.put("mode", req.mode());
        plan.put("title", title);
        plan.put("logline", logline);

        if ("video".equals(req.mode())) {
            buildVideo(plan, req);
        } else {
            buildImage(plan, req);
        }
        try {
            return mapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("stub 序列化失败", e);
        }
    }

    private void buildImage(ObjectNode plan, LlmRequest req) {
        String subject = clip(req.briefText(), 90);
        plan.put("prompt_zh", req.briefText());
        plan.put("positive_prompt", pad("cinematic still, " + subject
                + ", volumetric light, filmic color, shallow depth of field, 35mm", 40));
        plan.put("negative_prompt", String.join(", ", DirectorPlanValidator.DEFAULT_NEGATIVE));

        ObjectNode camera = plan.putObject("camera");
        camera.put("focal_mm", 35);
        camera.put("shot_size", "wide");
        camera.put("angle", "low");

        plan.put("lighting", "soft practical light, warm bounce, low ambient");
        plan.putArray("palette").add("#0B1C22").add("#C7B7A3").add("#8FB9B4");

        AspectPixels.Dim dim = AspectPixels.forImage(req.aspectRatio());
        ObjectNode params = plan.putObject("params");
        params.put("width", dim.width());
        params.put("height", dim.height());
        params.put("steps", 30);
        params.put("cfg", 5.5);
        params.put("sampler", "dpmpp_2m_karras");
        params.putNull("seed");
        plan.putArray("variations");
    }

    private void buildVideo(ObjectNode plan, LlmRequest req) {
        BigDecimal duration = req.durationSec() == null ? new BigDecimal("12") : req.durationSec();
        plan.put("duration_sec", duration);
        plan.put("aspect_ratio", req.aspectRatio());

        ObjectNode script = plan.putObject("script");
        script.put("theme", firstLine(req.briefText()));
        ArrayNode acts = script.putArray("acts");
        acts.addObject().put("name", "setup").put("start_sec", 0)
                .put("end_sec", duration).put("purpose", "围绕 Brief 建立镜头组");

        ObjectNode audio = plan.putObject("audio");
        audio.put("music_mood", "ambient, restrained");
        audio.putArray("sfx").add("ambient room tone");
        audio.put("vo", "");

        ObjectNode editPlan = plan.putObject("edit_plan");
        editPlan.put("fps", 30);
        editPlan.put("transition_default", "cut");
        editPlan.put("subtitle", false);

        List<BigDecimal> parts = splitDuration(duration, targetShotCount(duration));
        ArrayNode shots = plan.putArray("shots");
        Random rnd = new Random(20260905L); // 固定种子，QA 可复现
        String subject = clip(req.briefText(), 80);
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            ObjectNode s = shots.addObject();
            s.put("shot_no", i + 1);
            s.put("duration_sec", parts.get(i));
            s.put("shot_size", SHOT_SIZES.get(i % SHOT_SIZES.size()));
            s.put("camera_move", CAMERA_MOVES.get(i % CAMERA_MOVES.size()));
            s.put("action", (i == 0 ? "establish: " : "continue: ") + subject
                    + "，镜内节奏与情绪推进");
            s.put("positive_prompt", pad("shot " + (i + 1) + ": " + subject
                    + ", " + CAMERA_MOVES.get(i % CAMERA_MOVES.size())
                    + ", " + SHOT_SIZES.get(i % SHOT_SIZES.size()) + " shot"
                    + ", cinematic lighting, film grain, 24fps feel", 20));
            s.put("negative_prompt", String.join(", ", DirectorPlanValidator.DEFAULT_NEGATIVE));
            s.put("seed_lock", true);
            s.putNull("ref_shot_no");
            // 让不同镜在 stub 下有可区分的构图参数（占位，真实引擎由 Model Preset 决定）
            s.put("seed", rnd.nextLong());
        }
    }

    /** 目标镜数：§10.3 视频默认 4–8 镜/12s；按时长线性落在 [4,8]，且 ≤ ceil(d/1.5)。 */
    private static int targetShotCount(BigDecimal duration) {
        int sec = duration.setScale(0, RoundingMode.HALF_UP).intValueExact();
        int approx = Math.max(4, Math.min(8, (int) Math.round(sec / 3.0)));
        int maxByRule = duration.divide(new BigDecimal("1.5"), 0, RoundingMode.CEILING).intValueExact();
        return Math.min(approx, Math.max(4, maxByRule));
    }

    /** 时长切分（整秒网格，保证总和精确；parts 超界时收敛到 [4,8] 内合理值）。 */
    private static List<BigDecimal> splitDuration(BigDecimal duration, int requestedParts) {
        int totalSec = duration.setScale(0, RoundingMode.HALF_UP).intValueExact();
        int maxByRule = duration.divide(new BigDecimal("1.5"), 0, RoundingMode.CEILING).intValueExact();
        int parts = Math.max(1, Math.min(requestedParts, Math.min(totalSec, maxByRule)));
        int base = totalSec / parts;
        int rem = totalSec % parts;
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            out.add(new BigDecimal(base + (i < rem ? 1 : 0)));
        }
        return out;
    }

    private static String cleanTitle(String projectTitle, String brief) {
        if (projectTitle != null && !projectTitle.isBlank()) return projectTitle.trim();
        return clip(brief.replaceAll("[\\n\\r\\t，。！？,.!?]", " ").trim(), 24);
    }

    private static String firstLine(String brief) {
        if (brief == null) return "";
        String one = brief.split("\\n")[0].trim();
        return clip(one, 120);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }

    /** 补齐到最短长度（同时兜底超长截断在 800 内）。 */
    private static String pad(String s, int minLen) {
        String out = s.trim();
        if (out.length() > 800) out = out.substring(0, 800).trim();
        while (out.length() < minLen) {
            out = out + ", refined detail";
        }
        return out;
    }

    @Override
    public String source() {
        return "stub";
    }
}
