package studio.weaveora.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成前置闸门的**纯函数**契约（{@link JobService#nodeServes} / {@link JobService#nodeAlive}）。
 *
 * <p>为什么要这个闸门（2026-09-17 线上事故）：GPU#2 的公网地址被平台映射到**别人的实例**，
 * 我们停了 worker 止血；此时用户点生成，任务会写进队列但永远没人 claim，界面只显示「排队中」——
 * 用户以为在跑。宁可提交时就明确报「引擎没有在线节点」。
 *
 * <p>能力字段口径取自线上 {@code worker_nodes.capabilities} 真实值：
 * <ul>
 *   <li>自托管：{@code {"gpu":"comfy","audio":true,"engine":"gpu",…}}</li>
 *   <li>云：{@code {"gpu":"cloud","engine":"cloud",…}}</li>
 *   <li>历史噪音：{@code {"gpu":"stub-cpu",…}} —— **不算**任何一个在线车道</li>
 * </ul>
 */
class JobEngineGateTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode caps(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void gpuLaneAcceptsComfyNodeAndRejectsCloudOrStubNodes() {
        var comfy = caps("{\"gpu\":\"comfy\",\"audio\":true,\"engine\":\"gpu\"}");
        var cloud = caps("{\"gpu\":\"cloud\",\"engine\":\"cloud\"}");
        var stubCpu = caps("{\"gpu\":\"stub-cpu\"}");
        assertTrue(JobService.nodeServes("gpu", comfy));
        assertFalse(JobService.nodeServes("gpu", cloud));
        assertFalse(JobService.nodeServes("gpu", stubCpu), "stub-cpu 不是自托管 GPU 车道");
        assertFalse(JobService.nodeServes("gpu", null));
    }

    @Test
    void cloudLaneAcceptsOnlyCloudNodes() {
        var comfy = caps("{\"gpu\":\"comfy\",\"engine\":\"gpu\"}");
        var cloud = caps("{\"gpu\":\"cloud\",\"engine\":\"cloud\"}");
        assertTrue(JobService.nodeServes("cloud", cloud));
        assertFalse(JobService.nodeServes("cloud", comfy));
    }

    @Test
    void heartbeatWithinFiveMinuteGraceCountsAsOnline() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T12:35:00+08:00");
        assertTrue(JobService.nodeAlive(now.minusMinutes(1), now), "刚心跳过 → 在线");
        assertTrue(JobService.nodeAlive(now.minusMinutes(4), now), "宽限期内 → 在线");
        assertFalse(JobService.nodeAlive(now.minusMinutes(6), now), "超过宽限期 → 离线");
        assertFalse(JobService.nodeAlive(null, now), "没心跳过 → 离线");
    }
}
