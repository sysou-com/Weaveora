package studio.weaveora.script;

import org.junit.jupiter.api.Test;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;
import studio.weaveora.script.api.AiNextEpisodeRequest;
import studio.weaveora.script.api.AiNextEpisodeResult;
import studio.weaveora.script.api.AiPolishRequest;
import studio.weaveora.script.api.AiPolishResult;
import studio.weaveora.script.domain.Script;
import studio.weaveora.shared.api.BizException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分集/润色的**调用次数与字数口径**契约（2026-09-17 用户报「字数很少也很慢 + 弹 AI 生成内容超长」的回归测试）。
 *
 * <p>旧行为的三个真因（均已在 {@link ScriptAiService} 修）：
 * <ol>
 *   <li>循环上限 {@code plan.passes() + 1}，但每段提示词里的字数是**整集目标**（不是每段均摊）→ 目标 500 字也被要求
 *       「每段约 500 字」并写 2 段（实测 1335 字）；</li>
 *   <li>早停判据写死 {@code >= EPISODE_MIN_CHARS(4000)}，与用户设定的小目标脱钩 → 明明够了还继续写；</li>
 *   <li>「写超」阈值只有 {@code 目标 × 1.5}，而单段封顶是「目标 × 1.3（下限 800）」→ 老实按提示词上限写完也会被判写超，
 *       于是多花一次压缩调用 + 弹「写超了…」。</li>
 * </ol>
 *
 * <p>本测试用假网关**数调用次数**：小目标应当「1 次正文 + 1 次提纲 = 2 次」，且**不触发压缩**。
 */
class ScriptAiServicePlanTest {

    private static Script script() {
        return Script.create(null, null, "雨夜纸船", "短剧",
                "林知远：旧书店店主，执拗。", "起因…发展…高潮…结局。", "人与环境的对抗。",
                "开端/发展/转折/高潮/结局", "冷峻克制的对白。", "【深夜 · 旧书店二楼】");
    }

    /** 假网关：按调用类型返回指定长度的正文，并分类计数。 */
    private static final class FakeLlm implements DirectorLlm {
        int segmentCalls;
        int outlineCalls;
        int compressCalls;
        int polishCalls;
        /** 每段正文返回多少字（写超场景传超大值） */
        int segmentChars = 600;
        /** 压缩后返回多少字 */
        int compressedChars = 200;

        @Override
        public String generateJson(LlmRequest req) {
            String user = req.userPrompt() == null ? "" : req.userPrompt();
            if (user.contains("【待压缩内容】")) {
                compressCalls++;
                return "{\"value\":\"" + "压".repeat(compressedChars) + "\"}";
            }
            if (user.contains("【待润色的原文（本段）】")) {
                polishCalls++;
                return "{\"value\":\"" + "润".repeat(segmentChars) + "\"}";
            }
            if (user.contains("请输出 JSON 提纲")) {
                outlineCalls++;
                return "{\"segments\":[{\"index\":1,\"title\":\"起\",\"points\":[\"引入\"]}]}";
            }
            segmentCalls++;
            return "{\"title\":\"第 1 集\",\"summary\":\"摘要\",\"content\":\""
                    + "文".repeat(segmentChars) + "\",\"value\":\"" + "文".repeat(segmentChars) + "\"}";
        }

        @Override
        public String source() {
            return "llm";
        }
    }

    private static AiNextEpisodeResult next(FakeLlm llm, int targetChars) {
        ScriptAiService svc = new ScriptAiService(llm);
        return svc.nextEpisode(script(), List.of(), new AiNextEpisodeRequest(true, null, null, 1, targetChars));
    }

    // ---------------------------------------------------------------- 分集：小目标

    @Test
    void smallEpisodeTargetWritesOnlyOneSegmentAndNeverCompresses() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 600;   // 500 目标、封顶 800：允许范围内，不该被压缩
        AiNextEpisodeResult r = next(llm, 500);
        assertEquals(1, llm.segmentCalls, "500 字目标只应写 1 段（旧实现写 2 段）");
        assertEquals(0, llm.compressCalls, "允许范围内的稿子不该再花一次压缩调用");
        assertEquals(600, r.content().length());
        assertTrue(r.note() == null || r.note().isBlank(), "没有异常时不该弹提示：" + r.note());
    }

    @Test
    void smallEpisodeTargetStopsEvenWhenModelWritesMoreThanTarget() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 780;   // 达标（>500）且 ≤ 封顶 800
        AiNextEpisodeResult r = next(llm, 500);
        assertEquals(1, llm.segmentCalls, "一旦达标就必须早停，不能因为写死 4000 字下限再写一段");
        assertEquals(0, llm.compressCalls);
        assertTrue(r.content().length() <= ScriptPrompts.segmentCeiling(500), "不应超过本段封顶");
    }

    // ---------------------------------------------------------------- 分集：大目标分段

    @Test
    void largerTargetSplitsIntoPlannedSegmentsAndStopsAtTarget() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 2300;   // 4400 → 2 段 × 2200
        AiNextEpisodeResult r = next(llm, 4400);
        assertEquals(2, llm.segmentCalls, "4400 字应恰好 2 段（第 2 段写完即达标）");
        assertEquals(0, llm.compressCalls);
        assertEquals(2 * 2300 + 2, r.content().length(), "两段用 \\n\\n 拼接");
    }

    @Test
    void blankSegmentTriggersOneRetryThenKeepsWhatItHas() {
        // 第 2 段返回空正文 → 重试一次仍空 → 保留第 1 段（不静默丢）
        DirectorLlm flaky = new DirectorLlm() {
            int seg;

            @Override
            public String generateJson(LlmRequest req) {
                String user = req.userPrompt() == null ? "" : req.userPrompt();
                if (user.contains("请输出 JSON 提纲")) {
                    return "{\"segments\":[{\"index\":1,\"title\":\"起\",\"points\":[\"引入\"]}]}";
                }
                if (user.contains("【待压缩内容】")) {
                    return "{\"value\":\"" + "压".repeat(100) + "\"}";
                }
                seg++;
                // 第 1 段给正文；之后（含空正文重试）都返回空
                return seg == 1
                        ? "{\"content\":\"" + "文".repeat(1200) + "\"}"
                        : "{\"content\":\"\"}";
            }

            @Override
            public String source() {
                return "llm";
            }
        };
        AiNextEpisodeResult r = new ScriptAiService(flaky)
                .nextEpisode(script(), List.of(), new AiNextEpisodeRequest(true, null, null, 1, 4400));
        assertEquals(1200, r.content().length());
        assertTrue(r.note() != null && !r.note().isBlank(), "写少了必须如实告知（不静默）");
    }

    // ---------------------------------------------------------------- 分集：真的写飞才压缩

    @Test
    void modelIgnoringTheCeilingStillGetsOneCompressionPass() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 3000;   // 目标 500、封顶 800 → 真的写飞了
        llm.compressedChars = 450;
        AiNextEpisodeResult r = next(llm, 500);
        assertEquals(1, llm.compressCalls, "越过「封顶 × 段数」才该压缩，且只压一次");
        assertEquals(450, r.content().length());
        assertTrue(r.note().contains("已按目标字数自动精简"), "提示口径要中性、说清做了什么：" + r.note());
    }

    // ---------------------------------------------------------------- 润色「我自己写」的正文

    @Test
    void polishShortDraftIsOneCallAndKeepsOriginalFacts() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 320;
        String draft = "林知远推开旧书店的门。\n【雨声】\n他说：今天不卖书。";
        AiPolishResult r = new ScriptAiService(llm).polishEpisode(script(), List.of(),
                new AiPolishRequest(draft, null, null));
        assertEquals(1, llm.polishCalls, "短稿润色应当只有一次调用（无提纲、无压缩）");
        assertEquals(320, r.content().length());
        assertEquals(draft.length(), r.originalChars());
    }

    @Test
    void polishSplitsLongDraftIntoChunks() {
        FakeLlm llm = new FakeLlm();
        llm.segmentChars = 2200;
        String draft = ("一".repeat(2000) + "\n" + "二".repeat(2000) + "\n" + "三".repeat(2000));
        AiPolishResult r = new ScriptAiService(llm).polishEpisode(script(), List.of(),
                new AiPolishRequest(draft, "台词更冷", 2000));
        assertEquals(3, llm.polishCalls, "6000 字草稿按 2200 字/块应切 3 块");
        assertTrue(r.note().contains("长文分 3 段润色"), r.note());
        assertTrue(r.content().length() > 0);
    }

    @Test
    void polishRejectsEmptyDraft() {
        FakeLlm llm = new FakeLlm();
        assertThrows(BizException.class, () -> new ScriptAiService(llm).polishEpisode(script(), List.of(),
                new AiPolishRequest("   ", null, null)));
        assertEquals(0, llm.polishCalls, "空正文不该调用 LLM");
    }
}
