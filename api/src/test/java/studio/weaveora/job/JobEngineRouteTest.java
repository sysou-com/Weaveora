package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P13：任务执行面路由 + running 回收口径（回归防护）。
 *
 * <p>线上事故（2026-09-13，项目「那宝玉恍恍惚惚」第 1 镜）连着踩了两个坑：
 * <ol>
 *   <li>对口型任务 {@code engine_route=cloud} → 被 VPS 云 worker 领走 → 云上没有
 *       {@code WEAVEORA_LIPSYNC_WORKFLOW} → 创建后 70ms 就失败。根因：自托管 kind 的
 *       「必须本机 GPU」规则只写在通用分支，lipsync 在到达那行前就 return 了。</li>
 *   <li>修好路由后任务真的在本机跑了，却在第 15 分钟被判 {@code WORKER_STUCK}，
 *       而 worker 心跳一直正常、ComfyUI 已经跑到 5/8。根因：回收器只比 {@code startedAt}，
 *       硬编码 15min，完全没看 worker 心跳（错误信息却写「无心跳完成」）。</li>
 * </ol>
 */
class JobEngineRouteTest {

    // ---------- 执行面路由 ----------

    @Test
    void selfHostedKindsAlwaysGpuEvenWhenUserChoseCloud() {
        for (String kind : new String[]{"lipsync", "voice", "bgm"}) {
            assertEquals("gpu", JobService.routeForKind(kind, "cloud"),
                    kind + " 是自托管执行面，必须固定 gpu（云节点不会认领）");
            assertEquals("gpu", JobService.routeForKind(kind, "gpu"));
        }
    }

    @Test
    void otherKindsFollowUserSetting() {
        // 出图/视频尊重用户选择（云端模型确实能跑这些）
        assertEquals("cloud", JobService.routeForKind("clip", "cloud"));
        assertEquals("cloud", JobService.routeForKind("still", "cloud"));
        assertEquals("gpu", JobService.routeForKind("clip", "gpu"));
        assertEquals("gpu", JobService.routeForKind("still", "gpu"));
        assertEquals("gpu", JobService.routeForKind("portrait", "gpu"));
    }

    @Test
    void nullKindIsNotTreatedAsSelfHosted() {
        // Set.of(...).contains(null) 会抛 NPE，实现里必须先判 null
        assertEquals("cloud", JobService.routeForKind(null, "cloud"));
    }

    // ---------- running 回收口径 ----------

    @Test
    void longRunningJobIsKeptWhileWorkerHeartbeats() {
        // 对口型一个镜实测 25–30min：worker 心跳正常就不该回收
        assertFalse(JobService.shouldReap(true, false));
    }

    @Test
    void jobIsReapedWhenWorkerDied() {
        assertTrue(JobService.shouldReap(false, false));
        assertTrue(JobService.shouldReap(false, true));
    }

    @Test
    void jobIsReapedPastHardCapEvenIfWorkerAlive() {
        // worker 不死但任务永不结束 → 不能永久占着 GPU
        assertTrue(JobService.shouldReap(true, true));
    }
}
