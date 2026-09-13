package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P13：任务执行面路由（回归防护）。
 *
 * <p>线上事故（2026-09-13，项目「那宝玉恍恍惚惚」第 1 镜）：对口型任务
 * {@code engine_route=cloud} → 被云 worker（VPS）领走 → 云上没有
 * {@code WEAVEORA_LIPSYNC_WORKFLOW} → 创建后 70ms 就失败
 * {@code LIPSYNC_ERROR 未配置对口型工作流}。
 *
 * <p>根因：{@code createLipsyncJobs} 自己调 {@code resolveEngine(userId,"clip")}，
 * 而该用户 {@code video_engine=cloud}；「自托管 kind 固定走本机 GPU」的规则只写在
 * 通用分支里（lipsync 在此之前就 return 了）。配音/配乐的「重生成」也踩了同一个坑。
 */
class JobEngineRouteTest {

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
        // 出图/视频尊重用户的 引擎 选择（云端模型确实能跑这些）
        assertEquals("cloud", JobService.routeForKind("clip", "cloud"));
        assertEquals("cloud", JobService.routeForKind("still", "cloud"));
        assertEquals("gpu", JobService.routeForKind("clip", "gpu"));
        assertEquals("gpu", JobService.routeForKind("still", "gpu"));
        assertEquals("gpu", JobService.routeForKind("portrait", "gpu"));
    }

    @Test
    void nullKindIsNotTreatedAsSelfHosted() {
        assertEquals("cloud", JobService.routeForKind(null, "cloud"));
    }
}
