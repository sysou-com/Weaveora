package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参考图「槽号 ↔ 图片」一致性守卫（2026-09-29，用户裁定回滚 mentionOrder 后的防回归测试）。
 *
 * <p>背景（线上实测第 4 镜）：2026-09-27 上线的「按正词提及顺序重排参考图槽位」只重排了图片数组，
 * 没有重写正词里 LLM 写死的 {@code image N} 标注 ⇒ 正词说「宝玉(image 1)」而 image1 实际是警幻，
 * 症状＝「出现两个宝玉 / 警幻消失」。该重排已回滚；本测试锁住「不一致必须能被检出」这条不变式。
 */
class RefSlotNumberConsistencyTest {

    /** 真实线上数据：09-27 23:23 之后全部第 4 镜任务都是这个错位。 */
    @Test
    void detectsProductionMismatch() {
        // 正词（节选，来自 job 0a000003-a0e3-16a8-81a0-e8b99aec000f）
        String text = "雾气氤氲；警幻（image 3）急追而至，挥袖阻拦，宝玉(image 1)回身发问，可卿（image 2）贴近宝玉；";
        // 实际送入顺序（payload.referenceSubjects 实测）
        List<String> subjects = List.of("警幻", "宝玉", "可卿");
        List<String> bad = JobService.slotNumberMismatches(subjects, text);
        assertEquals(3, bad.size(), "三个槽号全部错位，应全部检出：" + bad);
        assertTrue(bad.stream().anyMatch(s -> s.contains("宝玉") && s.contains("image1")), bad.toString());
        assertTrue(bad.stream().anyMatch(s -> s.contains("警幻") && s.contains("image3")), bad.toString());
    }

    /** 09-27 23:23 之前的线上数据：文字编号与实际顺序一致 → 不得报错。 */
    @Test
    void acceptsConsistentChineseSlots() {
        String text = "警幻（image 3）急追而至，宝玉(image 1)回身发问，可卿（image 2）贴近宝玉；";
        // 当时实际送入顺序（payload.referenceSubjects 实测）
        assertEquals(List.of(), JobService.slotNumberMismatches(List.of("宝玉", "可卿", "警幻"), text));
    }

    /** 英文口径（BFL / ComfyUI 模板）同样要认。 */
    @Test
    void acceptsConsistentEnglishSlots() {
        String text = "Jinghuan (Reference Image 3) rushes up; Baoyu (image 1) turns back; "
                + "Keqing (Reference Image 2) draws close.";
        assertEquals(List.of(), JobService.slotNumberMismatches(List.of("Baoyu", "Keqing", "Jinghuan"), text));
    }

    /** 英文口径下的错位同样要检出。 */
    @Test
    void detectsEnglishMismatch() {
        String text = "Jinghuan (Reference Image 3) rushes up; Baoyu (image 1) turns back; "
                + "Keqing (Reference Image 2) draws close.";
        // 实际顺序换成 警幻/Baoyu/Keqing → 编号全错
        assertEquals(3, JobService.slotNumberMismatches(
                List.of("Jinghuan", "Baoyu", "Keqing"), text).size());
    }

    /** 正词里没写编号（如关键帧只靠剧情句）→ 不报错。 */
    @Test
    void ignoresTextWithoutSlotAnnotations() {
        assertEquals(List.of(), JobService.slotNumberMismatches(
                List.of("宝玉", "可卿", "警幻"), "宝玉回身发问，可卿贴近宝玉，警幻追来。"));
    }

    /** 越界编号要报出来。 */
    @Test
    void flagsOutOfRangeSlot() {
        List<String> bad = JobService.slotNumberMismatches(
                List.of("宝玉", "可卿"), "宝玉（image 3）回头。");
        assertEquals(1, bad.size(), bad.toString());
    }

    @Test
    void noopForDegenerateInputs() {
        assertEquals(List.of(), JobService.slotNumberMismatches(List.of("宝玉"), "宝玉(image 1)"));
        assertEquals(List.of(), JobService.slotNumberMismatches(List.of("宝玉", "可卿"), ""));
        assertEquals(List.of(), JobService.slotNumberMismatches(List.of(), "任何文本"));
        assertEquals(List.of(), JobService.slotNumberMismatches(null, "任何文本"));
    }
}
