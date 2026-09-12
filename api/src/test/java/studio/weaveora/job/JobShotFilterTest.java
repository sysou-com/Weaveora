package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P12 分镜封版 / 勾选的过滤口径（纯函数，不起 Spring）。
 *
 * <p>口径：
 * <ul>
 *   <li>批量（不传 shotNos）→ 跳过已封版镜；</li>
 *   <li>显式勾选 shotNos → 按勾选跑（含封版镜，因为是明确要求）；</li>
 *   <li>锁为空 → 原样返回。</li>
 * </ul>
 */
class JobShotFilterTest {

    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();
    private static final UUID S4 = UUID.randomUUID();
    private static final List<UUID> ALL = List.of(S1, S2, S3, S4);
    private static final Map<UUID, Integer> NO = Map.of(S1, 1, S2, 2, S3, 3, S4, 4);

    private static List<UUID> filter(List<Integer> only, Set<Integer> locked) {
        return JobService.applyShotFilter(ALL, only, locked, NO::get);
    }

    @Test
    void noFilterKeepsEverything() {
        assertEquals(ALL, filter(null, Set.of()));
        assertEquals(ALL, filter(List.of(), Set.of()));
    }

    @Test
    void batchSkipsSealedShots() {
        // 第 2、3 镜封版 → 批量只跑 1、4
        assertEquals(List.of(S1, S4), filter(null, Set.of(2, 3)));
    }

    @Test
    void allSealedMeansNothingToDo() {
        assertEquals(List.of(), filter(null, Set.of(1, 2, 3, 4)));
    }

    @Test
    void explicitPicksWinOverNothingButStillKeepOrder() {
        // 只勾 3、1 → 保持原清单顺序（1、3），且不因封版被剔（调用方已按 explicit 传空锁）
        assertEquals(List.of(S1, S3), filter(List.of(3, 1), Set.of()));
    }

    @Test
    void explicitPickOfSealedShotIsHonored() {
        // 用户在弹窗里显式勾了已封版的第 2 镜 → 照跑（explicit 时调用方传 locked=空）
        assertEquals(List.of(S2), filter(List.of(2), Set.of()));
    }

    @Test
    void explicitPickThenLockFilterAppliesIfBothGiven() {
        // 防御性：就算两个都给了，也要先按勾选再剔封版
        assertEquals(List.of(S1), filter(List.of(1, 2), Set.of(2)));
    }

    @Test
    void unknownShotNumbersYieldEmpty() {
        assertEquals(List.of(), filter(List.of(99), Set.of()));
    }
}
