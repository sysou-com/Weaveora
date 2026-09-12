package studio.weaveora.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P12 云模型 schema 归一化：参数说明 + 参数映射。
 *
 * <p>这里用的 schema 是 {@code black-forest-labs/flux-2-klein-9b} 在 Replicate 上的真实
 * openapi_schema（2026-09 拉取）—— 就是它把参考图字段叫 {@code images}，而我们早期猜成
 * {@code input_images}，被 Replicate 静默忽略，导致「参考图完全没生效」。
 */
class ModelSchemaServiceTest {

    private static final String FLUX2 = """
            {"owner":"black-forest-labs","name":"flux-2-klein-9b","visibility":"public",
             "latest_version":{"id":"963f7b2c4aa2aaaa","created_at":"2026-09-01T00:00:00.000Z",
               "openapi_schema":{"components":{"schemas":{"Input":{
                 "type":"object",
                 "required":["prompt"],
                 "properties":{
                   "seed":{"type":"integer","description":"Random seed. Set for reproducible generation"},
                   "images":{"type":"array","items":{"type":"string","maxItems":5},"default":[],
                             "description":"List of input images for image-to-image generation. Maximum 5 images."},
                   "prompt":{"type":"string","description":"Text prompt for image generation."},
                   "go_fast":{"type":"boolean","default":true,"description":"Run faster predictions"},
                   "megapixels":{"enum":["0.25","1","4"],"default":"1","description":"Resolution in megapixels"},
                   "aspect_ratio":{"enum":["1:1","16:9","9:16"],"default":"1:1",
                                   "description":"Aspect ratio for the generated image."},
                   "output_format":{"enum":["jpg","png"],"default":"jpg","description":"Format of the output images"},
                   "output_quality":{"type":"integer","default":95,"maximum":100,
                                     "description":"Quality when saving the output images, from 0 to 100."},
                   "disable_safety_checker":{"type":"boolean","default":false,"description":"Disable safety checker"}
                 }}}}}}}}
            """;

    private ObjectNode normalized() throws Exception {
        var raw = new ObjectMapper().readTree(FLUX2);
        return new ModelSchemaService().normalize("black-forest-labs/flux-2-klein-9b", raw);
    }

    @Test
    void derivesRefsFieldFromSchema() throws Exception {
        var s = normalized();
        var m = s.path("mapping");
        // 关键断言：参考图字段来自 schema（images），不是我们猜的 input_images
        assertEquals("images", m.path("refs").asText());
        assertTrue(m.path("refsIsArray").asBoolean());
        assertEquals(5, m.path("refsMax").asInt());
        assertEquals("prompt", m.path("prompt").asText());
        assertEquals("aspect_ratio", m.path("aspect").asText());
        assertEquals("seed", m.path("seed").asText());
        // 该模型没有这些字段 → 映射里就不该有（避免发出去被静默忽略）
        assertTrue(m.path("negative").asText().isEmpty(), "FLUX.2 不支持 negative_prompt");
        assertTrue(m.path("width").asText().isEmpty());
        assertTrue(m.path("height").asText().isEmpty());
        assertTrue(m.path("steps").asText().isEmpty());
    }

    @Test
    void exposesConciseParamDoc() throws Exception {
        var s = normalized();
        assertEquals("replicate", s.path("provider").asText());
        assertEquals("963f7b2c4aa2aaaa", s.path("version").asText());
        var params = s.path("params");
        assertEquals(9, params.size(), "flux-2-klein-9b 共 9 个输入参数");

        ObjectNode byName = new ObjectMapper().createObjectNode();
        params.forEach(p -> byName.set(p.path("name").asText(), (ObjectNode) p));

        assertEquals("array", byName.path("images").path("type").asText());
        assertEquals("refs", byName.path("images").path("group").asText());
        assertEquals(5, byName.path("images").path("maxItems").asInt());
        assertFalse(byName.path("images").path("userEditable").asBoolean(), "参考图由系统填");

        assertEquals("enum", byName.path("aspect_ratio").path("type").asText());
        assertEquals("1:1", byName.path("aspect_ratio").path("default").asText());
        assertEquals(3, byName.path("aspect_ratio").path("enum").size());

        assertEquals("quality", byName.path("megapixels").path("group").asText());
        assertTrue(byName.path("megapixels").path("userEditable").asBoolean(), "画质允许用户改");
        assertEquals("output_quality", byName.path("output_quality").path("name").asText());
        assertEquals("quality", byName.path("output_quality").path("group").asText());
        assertTrue(byName.path("output_quality").path("userEditable").asBoolean());

        assertTrue(byName.path("prompt").path("required").asBoolean(), "prompt 是 required");
        assertFalse(byName.path("prompt").path("userEditable").asBoolean(), "prompt 由系统填");
        assertFalse(byName.path("seed").path("userEditable").asBoolean());
        assertTrue(byName.path("disable_safety_checker").path("userEditable").asBoolean());
    }

    @Test
    void notesFlagUnsupportedNegativePrompt() throws Exception {
        String notes = normalized().path("notes").toString();
        assertTrue(notes.contains("negative_prompt"), "要提示负向提示词会被忽略：" + notes);
    }

    @Test
    void sanitizeKeepsOnlyKnownKeysAndRightTypes() throws Exception {
        var raw = new ObjectMapper().readTree(FLUX2);
        var svc = new ModelSchemaService();
        var schema = svc.normalize("black-forest-labs/flux-2-klein-9b", raw);
        var user = new ObjectMapper().readTree("""
                {"megapixels":"4","output_quality":88,"go_fast":false,
                 "aspect_ratio":"16:9","not_a_param":123,"prompt":"注入攻击","output_format":"webp"}
                """);
        var out = svc.sanitizeParams(schema, user);
        assertEquals("4", out.path("megapixels").asText());
        assertEquals(88, out.path("output_quality").asInt());
        assertEquals(false, out.path("go_fast").asBoolean());
        assertEquals("16:9", out.path("aspect_ratio").asText());
        assertFalse(out.has("not_a_param"), "schema 里没有的键必须丢掉");
        assertFalse(out.has("prompt"), "系统独占字段不允许被全局参数覆盖");
        assertFalse(out.has("output_format"), "枚举之外的取值要丢掉（webp 不在 enum）");
    }

    @Test
    void resolvesAllOfRefEnums() throws Exception {
        // Replicate 对枚举型参数的**真实**写法：enum 在同级命名 schema 里，靠 allOf/$ref 引用。
        // 不解引用就会退化成 unknown → 拿不到 enum、用户也不能改画质（实测踩过）。
        String raw = """
                {"latest_version":{"id":"v9","openapi_schema":{"components":{"schemas":{
                  "Input":{"type":"object","properties":{
                    "prompt":{"type":"string"},
                    "megapixels":{"allOf":[{"$ref":"#/components/schemas/megapixels"}],
                                  "default":"1","description":"Resolution in megapixels"},
                    "aspect_ratio":{"allOf":[{"$ref":"#/components/schemas/aspect_ratio"}],
                                    "default":"1:1","description":"Aspect ratio for the generated image"},
                    "output_format":{"type":"string","default":"jpg"},
                    "images":{"type":"array","items":{"type":"string"},"default":[],
                              "description":"List of input images. Maximum 5 images."}
                  }},
                  "megapixels":{"type":"string","enum":["0.25","1","4"]},
                  "aspect_ratio":{"type":"string","enum":["1:1","16:9","9:16","match_input_image"]}
                }}}}}
                """;
        var s = new ModelSchemaService().normalize("black-forest-labs/flux-2-klein-9b",
                new ObjectMapper().readTree(raw));
        var byName = new ObjectMapper().createObjectNode();
        s.path("params").forEach(p -> byName.set(p.path("name").asText(), (ObjectNode) p));

        assertEquals("enum", byName.path("megapixels").path("type").asText(), "allOf 必须解开");
        assertEquals(3, byName.path("megapixels").path("enum").size());
        assertTrue(byName.path("megapixels").path("userEditable").asBoolean(), "画质要能改");
        assertEquals("quality", byName.path("megapixels").path("group").asText());
        assertEquals("1:1", byName.path("aspect_ratio").path("default").asText());
        assertEquals(4, byName.path("aspect_ratio").path("enum").size());
        assertEquals("quality", byName.path("aspect_ratio").path("group").asText());
        assertEquals("quality", byName.path("output_format").path("group").asText());
        // “Maximum 5 images” 只写在描述里 → 要能从描述抠出 refsMax
        assertEquals(5, s.path("mapping").path("refsMax").asInt());
        assertEquals(5, byName.path("images").path("maxItems").asInt());
    }

    @Test
    void detectsImageInputStyleRefsField() throws Exception {
        // 回归：bytedance/seedream-4 与 google/nano-banana 用 `image_input`，早期名单里没有它
        // → 界面错报「该模型没有参考图入口」，而模型明明支持 1-10 张参考图。
        String raw = """
                {"latest_version":{"id":"cf7d43199143","openapi_schema":{"components":{"schemas":{
                  "Input":{"type":"object","properties":{
                    "prompt":{"type":"string"},
                    "size":{"enum":["1K","2K","4K","custom"],"default":"2K"},
                    "width":{"type":"integer","default":2048},
                    "height":{"type":"integer","default":2048},
                    "max_images":{"type":"integer","default":1,
                                   "description":"Maximum number of images to generate"},
                    "image_input":{"type":"array","items":{"type":"string"},"default":[],
                                   "description":"Input image(s) for image-to-image generation. List of 1-10 images for single or multi-reference generation."},
                    "aspect_ratio":{"type":"string","default":"match_input_image"},
                    "enhance_prompt":{"type":"boolean","default":true}
                  }}}}}}}
                """;
        var s = new ModelSchemaService().normalize("bytedance/seedream-4",
                new ObjectMapper().readTree(raw));
        var m = s.path("mapping");
        assertEquals("image_input", m.path("refs").asText(), "必须认出 image_input");
        assertTrue(m.path("refsIsArray").asBoolean());
        assertEquals("size", m.path("size").asText(), "分辨率档位也要能识别");
        assertEquals("aspect_ratio", m.path("aspect").asText());
        assertTrue(s.path("notes").toString().indexOf("没有可识别的参考图") < 0,
                "不该再报「没有参考图入口」：" + s.path("notes"));

        // max_images 是“生成几张图”，不能被当成参考图字段
        var byName = new ObjectMapper().createObjectNode();
        s.path("params").forEach(p -> byName.set(p.path("name").asText(), (ObjectNode) p));
        assertEquals("refs", byName.path("image_input").path("group").asText());
        assertFalse(byName.path("image_input").path("userEditable").asBoolean());
    }

    @Test
    void guessesRefsFieldWhenNotInCandidateList() throws Exception {
        // 通用兜底：名单没命中也要能从 schema 里找出「收图的数组字段」（防止又漏一个新名字）
        String raw = """
                {"latest_version":{"id":"v1","openapi_schema":{"components":{"schemas":{
                  "Input":{"type":"object","properties":{
                    "prompt":{"type":"string"},
                    "num_images":{"type":"integer","default":1},
                    "source_photos":{"type":"array","items":{"type":"string"},
                                      "description":"reference photos of the character"}
                  }}}}}}}
                """;
        var s = new ModelSchemaService().normalize("acme/unknown-model",
                new ObjectMapper().readTree(raw));
        assertEquals("source_photos", s.path("mapping").path("refs").asText());
        assertTrue(s.path("mapping").path("refsGuessed").asBoolean());
    }

    @Test
    void textToImageModelHasNoRefsMapping() throws Exception {
        String t2i = """
                {"latest_version":{"id":"v1","openapi_schema":{"components":{"schemas":{"Input":{
                  "type":"object","required":["prompt"],
                  "properties":{"prompt":{"type":"string"},"width":{"type":"integer","default":1024},
                                "height":{"type":"integer","default":1024},
                                "negative_prompt":{"type":"string"}}}}}}}}
                """;
        var s = new ModelSchemaService().normalize("some/text2img", new ObjectMapper().readTree(t2i));
        var m = s.path("mapping");
        assertTrue(m.path("refs").asText().isEmpty(), "纯文生图模型没有参考图字段");
        assertEquals("width", m.path("width").asText());
        assertEquals("height", m.path("height").asText());
        assertEquals("negative_prompt", m.path("negative").asText());
        assertTrue(s.path("notes").toString().contains("参考图字段"),
                "要提示该模型无法用参考图：" + s.path("notes"));
    }
}
