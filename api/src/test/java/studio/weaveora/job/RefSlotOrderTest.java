package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 参考图槽位「按正词提及顺序」重排的纯函数契约（2026-09-28 用户裁定）。
 *
 * <p>背景（实测第 4 镜）：正词里第一个被提到、且被用户指定“只让它露清晰正脸”的是「警幻」，
 * 而槽位顺序是 1=宝玉 2=可卿 3=警幻 ⇒ 模型拿到「先说警幻、首图却是宝玉」的错位对应，
 * 表现为「警幻的脸长在可卿身上」「出现两个宝玉」。
 */
class RefSlotOrderTest {

    @Test
    void reordersWhenMentionOrderDiffersFromSlotOrder() {
        // 第4镜真实文本节选：警幻最先被提到，而槽位是 宝玉/可卿/警幻
        String text = "警幻自后方追来，挥袖急阻二人；宝玉回身望向警幻，可卿与宝玉并肩止步。";
        int[] ord = JobService.mentionOrder(List.of("宝玉", "可卿", "警幻"), text);
        assertArrayEquals(new int[] {2, 0, 1}, ord);
    }

    @Test
    void returnsNullWhenAlreadyAligned() {
        String text = "左侧宝玉侧身靠向右侧害羞的可卿，二人低语。";
        assertNull(JobService.mentionOrder(List.of("宝玉", "可卿"), text));
    }

    @Test
    void unmentionedSubjectsKeepRelativeOrderAndGoLast() {
        String text = "警幻摇头作答；宝玉发问。";   // 可卿没被提到
        int[] ord = JobService.mentionOrder(List.of("宝玉", "可卿", "警幻"), text);
        // 文本里首次出现位置：警幻=0、宝玉=7；可卿未提名 → 排最后（保持原相对顺序）
        assertArrayEquals(new int[] {2, 0, 1}, ord);
    }

    @Test
    void nullWhenSingleSubjectOrEmptyText() {
        assertNull(JobService.mentionOrder(List.of("宝玉"), "宝玉与可卿"));
        assertNull(JobService.mentionOrder(List.of("宝玉", "可卿"), ""));
        assertNull(JobService.mentionOrder(List.of(), "任何文本"));
        assertNull(JobService.mentionOrder(null, "任何文本"));
    }
}
