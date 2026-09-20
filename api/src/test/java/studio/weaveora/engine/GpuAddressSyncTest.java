package studio.weaveora.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import studio.weaveora.engine.api.GpuAddressSyncResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一键同步 GPU 地址（{@code POST /api/v1/me/engine-settings/sync-address}）的纯函数契约。
 *
 * <p>背景（2026-09-21 生产事故）：换 GPU 实例后只改了「端口」字段，services 里那些**显式填过**的 URL
 * 一个都没跟着变 → 出图/参考图上传打到已下线的旧端口，报
 * {@code urlopen error [Errno 111] Connection refused}，四次任务全失败。
 * 这里锁住三件事：① 命中旧主机的 URL 全改（含**同一个主机的不同旧端口**）；② 路径保留；
 * ③ 云 API（域名）与本机回环地址绝不误伤，且仍指向 IP 字面量的地方要报进 leftovers。
 */
class GpuAddressSyncTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 生产库 2026-09-21 的真实形态：同一个旧主机上并存两个旧端口 + 一处域名云 API + 一处回环。 */
    private static ObjectNode prodLike() {
        ObjectNode services = M.createObjectNode();
        services.set("tts", M.createObjectNode().put("url", "http://180.127.11.167:27458/audio"));
        services.set("music", M.createObjectNode()
                .put("engine", "comfy").put("url", "http://180.127.11.167:21282/bgm").put("ckpt", "ace.safetensors"));
        services.set("talk", M.createObjectNode().put("url", "http://180.127.11.167:21282/talk"));
        services.set("face", M.createObjectNode()
                .put("url", "http://180.127.11.167:27458").put("latentsyncDir", "/opt/weaveora-node/latentsync"));
        services.set("lipsync", M.createObjectNode()
                .put("comfyUrl", "http://180.127.11.167:27458")
                .put("workflow", "/opt/weaveora/lipsync_workflow_api.json").put("timeout", 1800).put("fps", 0));
        services.set("image", M.createObjectNode()
                .put("engine", "comfy").put("comfyUrl", "http://180.127.11.167:21282")
                .put("editWorkflow", "/opt/weaveora/workflows/qwen_image_edit_api.json")
                .put("steps", 40).put("cfg", 4.0).put("denoise", 1.0));
        services.set("transcribe", M.createObjectNode().put("url", "http://180.127.11.167:27458/audio"));
        // 不该被动的两类：云 API 域名 + worker 本机回环
        services.set("cloudDemo", M.createObjectNode().put("url", "https://ark.cn-beijing.volces.com/api/v3/images/generations"));
        services.set("localDemo", M.createObjectNode().put("url", "http://127.0.0.1:8188"));
        return services;
    }

    @Test
    void 所有命中旧主机的URL都改成新IP新端口_路径保留_云API与回环不动() {
        List<GpuAddressSyncResponse.Change> changes = new ArrayList<>();
        List<String> leftovers = new ArrayList<>();

        ObjectNode out = EngineSettingsService.rewriteAddresses(
                M, prodLike(), "180.127.11.167", "180.127.11.169", 27458, changes, leftovers);

        // ① 同一个旧主机的**两个不同旧端口**都要收口到新端口（21282 是更早一次换机留下的残值）
        assertEquals("http://180.127.11.169:27458/audio", out.path("tts").path("url").asText());
        assertEquals("http://180.127.11.169:27458/bgm", out.path("music").path("url").asText());
        assertEquals("http://180.127.11.169:27458/talk", out.path("talk").path("url").asText());
        assertEquals("http://180.127.11.169:27458", out.path("face").path("url").asText());
        assertEquals("http://180.127.11.169:27458", out.path("lipsync").path("comfyUrl").asText());
        assertEquals("http://180.127.11.169:27458", out.path("image").path("comfyUrl").asText());
        assertEquals("http://180.127.11.169:27458/audio", out.path("transcribe").path("url").asText());

        // ② 非 URL 的值（工作流路径 / 模型名 / 数字）原样保留
        assertEquals("/opt/weaveora/workflows/qwen_image_edit_api.json",
                out.path("image").path("editWorkflow").asText());
        assertEquals("ace.safetensors", out.path("music").path("ckpt").asText());
        assertEquals(40, out.path("image").path("steps").asInt());
        assertEquals("/opt/weaveora-node/latentsync", out.path("face").path("latentsyncDir").asText());

        // ③ 云 API（域名）与回环都不动、也不该被报成「改漏」
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/images/generations",
                out.path("cloudDemo").path("url").asText());
        assertEquals("http://127.0.0.1:8188", out.path("localDemo").path("url").asText());
        assertTrue(leftovers.isEmpty(), "域名与回环不该算改漏，实际：" + leftovers);

        // ④ 替换清单要逐字段可读（用户靠它确认没改漏）
        assertEquals(7, changes.size(), "应替换 7 处显式 URL，实际：" + changes);
        GpuAddressSyncResponse.Change image = changes.stream()
                .filter(c -> c.field().equals("services.image.comfyUrl")).findFirst().orElseThrow();
        assertEquals("http://180.127.11.167:21282", image.before());
        assertEquals("http://180.127.11.169:27458", image.after());
    }

    @Test
    void 旧主机相同但端口不同_仍然改成新端口() {
        ObjectNode services = M.createObjectNode();
        services.set("image", M.createObjectNode().put("comfyUrl", "http://10.0.0.9:8001"));
        List<GpuAddressSyncResponse.Change> changes = new ArrayList<>();
        List<String> leftovers = new ArrayList<>();

        ObjectNode out = EngineSettingsService.rewriteAddresses(
                M, services, "10.0.0.9", "10.0.0.10", 27458, changes, leftovers);

        assertEquals("http://10.0.0.10:27458", out.path("image").path("comfyUrl").asText());
        assertEquals(1, changes.size());
        assertTrue(leftovers.isEmpty());
    }

    @Test
    void 其它机器的IP会被报成改漏() {
        ObjectNode services = M.createObjectNode();
        services.set("image", M.createObjectNode().put("comfyUrl", "http://180.127.11.167:21282"));
        services.set("talk", M.createObjectNode().put("url", "http://223.109.239.30:21216/talk"));
        List<GpuAddressSyncResponse.Change> changes = new ArrayList<>();
        List<String> leftovers = new ArrayList<>();

        EngineSettingsService.rewriteAddresses(
                M, services, "180.127.11.167", "180.127.11.169", 27458, changes, leftovers);

        assertEquals(1, changes.size());
        assertEquals(1, leftovers.size(), "另一台机器的 IP 必须报出来，实际：" + leftovers);
        assertTrue(leftovers.get(0).contains("223.109.239.30"), leftovers.get(0));
    }

    @Test
    void 带方案的host与裸host与host端口三种写法都能解析() {
        assertEquals("180.127.11.169", EngineSettingsService.hostOf("http://180.127.11.169:27458"));
        assertEquals("180.127.11.169", EngineSettingsService.hostOf("180.127.11.169"));
        assertEquals("180.127.11.169", EngineSettingsService.hostOf("180.127.11.169:27458"));
        assertEquals("my-gpu.internal", EngineSettingsService.hostOf("https://my-gpu.internal/"));
        assertNull(EngineSettingsService.hostOf("   "));
    }

    @Test
    void 回环判定() {
        assertTrue(EngineSettingsService.isLoopback("127.0.0.1"));
        assertTrue(EngineSettingsService.isLoopback("localhost"));
        assertFalse(EngineSettingsService.isLoopback("180.127.11.169"));
    }
}
