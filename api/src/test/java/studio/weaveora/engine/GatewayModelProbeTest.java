package studio.weaveora.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关（OpenAI Images 兼容，如火山方舟）参数获取：
 * 无法自动拿 schema（方舟只有 /models 的模态与任务类型），所以支持「粘贴示例请求 → 解析字段名」。
 */
class GatewayModelProbeTest {

    /** 用户实际会粘贴的形态：带 curl 前缀的方舟请求。 */
    private static final String ARK_CURL = """
            curl https://ark.cn-beijing.volces.com/api/v3/images/generations \\
              -H "Content-Type: application/json" \\
              -H "Authorization: Bearer $ARK_API_KEY" \\
              -d '{
                "model": "doubao-seedream-5-0-260128",
                "prompt": "两个中国古装武将交战",
                "image": ["data:image/jpeg;base64,AAA", "https://example.com/ref.png"],
                "size": "2K",
                "watermark": false,
                "sequential_image_generation": "disabled"
              }'
            """;

    @Test
    void parsesArkCurlSample() {
        ObjectNode s = GatewayModelProbe.parseSample(ARK_CURL);
        var m = s.path("mapping");
        assertEquals("image", m.path("refs").asText(), "识别参考图字段");
        assertTrue(m.path("refsIsArray").asBoolean(), "image 是数组=可多张");
        assertEquals("prompt", m.path("prompt").asText());
        assertEquals("size", m.path("size").asText());
        assertEquals("doubao-seedream-5-0-260128", s.path("model").asText());
        assertEquals(6, s.path("params").size(), "model + prompt + image + size + watermark + sequential_image_generation");
        // watermark: false 是布尔，要能被当成可调参数而不是参考图
        var byName = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        s.path("params").forEach(p -> byName.set(p.path("name").asText(), (ObjectNode) p));
        assertEquals("boolean", byName.path("watermark").path("type").asText());
        assertTrue(byName.path("watermark").path("userEditable").asBoolean());
        assertFalse(byName.path("image").path("userEditable").asBoolean(), "参考图不能被全局参数改");
        assertTrue(s.path("notes").toString().contains("张数上限"));
    }

    @Test
    void parsesReplicateStyleInputWrapper() {
        String body = "{\"input\":{\"prompt\":\"x\",\"images\":[\"u1\"],\"aspect_ratio\":\"16:9\"}}";
        ObjectNode s = GatewayModelProbe.parseSample(body);
        assertEquals("images", s.path("mapping").path("refs").asText());
        assertEquals("aspect_ratio", s.path("mapping").path("aspect").asText());
    }

    @Test
    void parsesComfyStyleInputFiles() {
        String body = "{\"prompt\":\"x\",\"input_images\":[\"u1\"],\"num_inference_steps\":28}";
        ObjectNode s = GatewayModelProbe.parseSample(body);
        assertEquals("input_images", s.path("mapping").path("refs").asText());
    }

    @Test
    void warnsWhenNoRefsFieldInSample() {
        ObjectNode s = GatewayModelProbe.parseSample("{\"prompt\":\"纯文生图\",\"n\":1}");
        assertTrue(s.path("mapping").path("refs").asText("").isEmpty());
        assertTrue(s.path("notes").toString().contains("没有找到参考图字段"));
    }

    @Test
    void emptySampleGivesActionableNote() {
        ObjectNode s = GatewayModelProbe.parseSample("   ");
        assertEquals(0, s.path("params").size());
        assertTrue(s.path("notes").toString().contains("粘贴"));
    }

    @Test
    void modelsUrlDerivation() {
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/models",
                GatewayModelProbe.modelsUrl("https://ark.cn-beijing.volces.com/api/v3/images/generations"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/models",
                GatewayModelProbe.modelsUrl("https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals("https://gw.example.com/v1/models",
                GatewayModelProbe.modelsUrl("https://gw.example.com/v1/images/generations"));
    }
}
