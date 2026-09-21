package studio.weaveora.engine;

import org.junit.jupiter.api.Test;
import studio.weaveora.engine.api.GpuAddressSyncResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker env（{@code weaveora-gpu-worker.env}）一键同步的纯函数契约。
 *
 * <p>为什么单独锁：这是**第二个真源**。DB 里的 services 随任务下发（生效值），env 只在 DB 字段为空时兜底，
 * 但两者不一致时排查会变得极其别扭（页面看着改了、某条链路仍打旧地址）。
 * 所以同步 env 必须：只碰白名单键、路径保留、注释与其它配置原样不动、已是新值时不重写（幂等）。
 */
class WorkerEnvSyncTest {

    /** 生产 env 的真实形态（节选，含注释行、非白名单键、被注释掉的键）。 */
    private static final List<String> PROD_ENV = List.of(
            "# Weaveora GPU worker —— 引擎在新 GPU 服务器",
            "WEAVEORA_API_BASE=http://127.0.0.1:8080",
            "WEAVEORA_WORKER_MODE=comfy",
            "",
            "# 引擎地址：走公网单端口网关（180.127.11.167:21282）",
            "WEAVEORA_COMFY_URL=http://180.127.11.167:21282",
            "WEAVEORA_TTS_URL=http://180.127.11.167:21282/audio",
            "WEAVEORA_MUSIC_URL=http://180.127.11.167:21282/bgm",
            "WEAVEORA_FACE_URL=http://180.127.11.167:21282",
            "WEAVEORA_MUSIC_ENGINE=comfy",
            "#WEAVEORA_IMAGE_LORA=Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16.safetensors",
            "WEAVEORA_IMAGE_LORA_STRENGTH=1.0",
            "WEAVEORA_IMAGE_UPSCALE=seedvr2");

    private static final String NEW_BASE = "http://180.127.11.169:27458";

    @Test
    void 白名单键换成新地址_路径保留_其它行与注释原样不动() {
        WorkerEnvSyncService.Rewrite r = WorkerEnvSyncService.rewrite(PROD_ENV, NEW_BASE);

        List<String> out = r.lines();
        assertEquals("# Weaveora GPU worker —— 引擎在新 GPU 服务器", out.get(0));
        assertEquals("WEAVEORA_API_BASE=http://127.0.0.1:8080", out.get(1), "API base 不是 GPU 网关，不能动");
        assertEquals("WEAVEORA_COMFY_URL=" + NEW_BASE, out.get(5));
        assertEquals("WEAVEORA_TTS_URL=" + NEW_BASE + "/audio", out.get(6), "路径必须保留");
        assertEquals("WEAVEORA_MUSIC_URL=" + NEW_BASE + "/bgm", out.get(7));
        assertEquals("WEAVEORA_FACE_URL=" + NEW_BASE, out.get(8));
        assertEquals("WEAVEORA_MUSIC_ENGINE=comfy", out.get(9));
        assertEquals("#WEAVEORA_IMAGE_LORA=Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16.safetensors",
                out.get(10), "被注释掉的键不能被动");
        assertEquals("WEAVEORA_IMAGE_UPSCALE=seedvr2", out.get(12));

        assertEquals(4, r.changes().size(), "应只改 4 个白名单键，实际：" + r.changes());
        assertEquals("WEAVEORA_COMFY_URL", r.changes().get(0).field());
        assertEquals("http://180.127.11.167:21282", r.changes().get(0).before());
        assertEquals(NEW_BASE, r.changes().get(0).after());
    }

    @Test
    void 已是新地址时零改动_幂等() {
        List<String> already = List.of(
                "WEAVEORA_COMFY_URL=" + NEW_BASE,
                "WEAVEORA_TTS_URL=" + NEW_BASE + "/audio");
        WorkerEnvSyncService.Rewrite r = WorkerEnvSyncService.rewrite(already, NEW_BASE);
        assertTrue(r.changes().isEmpty(), "已是新值不该报改动：" + r.changes());
        assertEquals(already, r.lines());
    }

    @Test
    void 白名单键为空或非URL时不动它() {
        List<String> lines = List.of(
                "WEAVEORA_COMFY_URL=",
                "WEAVEORA_FACE_URL=127.0.0.1:8093",
                "WEAVEORA_TTS_URL=/relative/path");
        WorkerEnvSyncService.Rewrite r = WorkerEnvSyncService.rewrite(lines, NEW_BASE);
        assertTrue(r.changes().isEmpty(), "空值/无 scheme 的值不该被改写：" + r.changes());
        assertEquals(lines, r.lines());
    }

    @Test
    void 行内注释保留() {
        List<String> lines = List.of("WEAVEORA_TTS_URL=http://1.2.3.4:9/audio # 网关");
        WorkerEnvSyncService.Rewrite r = WorkerEnvSyncService.rewrite(lines, NEW_BASE);
        assertEquals("WEAVEORA_TTS_URL=" + NEW_BASE + "/audio # 网关", r.lines().get(0));
        assertEquals(1, r.changes().size());
    }

    @Test
    void 解析KEY与值() {
        assertEquals("WEAVEORA_COMFY_URL", WorkerEnvSyncService.keyOf("WEAVEORA_COMFY_URL=http://x"));
        assertEquals("WEAVEORA_COMFY_URL", WorkerEnvSyncService.keyOf("  WEAVEORA_COMFY_URL = http://x"));
        assertNull(WorkerEnvSyncService.keyOf("#WEAVEORA_COMFY_URL=http://x"));
        assertNull(WorkerEnvSyncService.keyOf(""));
        assertNull(WorkerEnvSyncService.keyOf("没有等号"));
        assertEquals("http://x", WorkerEnvSyncService.valueOf("WEAVEORA_COMFY_URL=http://x"));
    }
}
