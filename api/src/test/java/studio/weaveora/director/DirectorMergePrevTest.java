package studio.weaveora.director;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导演新一版的「用户字段继承」单测。
 *
 * <p>实际踩到的坑：点「导演再给一版」后，用户配好的**角色音色绑定 / 克隆音色库 / 镜内多段语音**
 * 全部凭空消失 —— 因为 LLM 产出的方案里没有这些字段，而继承逻辑只覆盖了 zh/narration。
 */
class DirectorMergePrevTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode plan(int... shotNos) {
        ObjectNode p = mapper.createObjectNode();
        p.put("mode", "video");
        ArrayNode shots = p.putArray("shots");
        for (int no : shotNos) {
            ObjectNode s = shots.addObject();
            s.put("shot_no", no);
            s.put("duration_sec", 4);
            s.put("positive_prompt", "ancient chinese battlefield, cinematic wide shot");
            s.put("negative_prompt", "text");
        }
        return p;
    }

    private ObjectNode shotOf(ObjectNode plan, int index) {
        return (ObjectNode) plan.path("shots").get(index);
    }

    @Test
    void carriesOverMultiSegmentNarrations() {
        ObjectNode prev = plan(1, 2);
        ArrayNode arr = shotOf(prev, 1).putArray("narrations");
        arr.addObject().put("at_sec", 2.0).put("end_sec", 3.6)
                .put("text", "谁敢与我决一死战！").put("subject", "吕布").put("speed", 1.05);

        ObjectNode now = plan(1, 2);
        DirectorService.mergePrevMeta(now, prev);

        JsonNode got = shotOf(now, 1).path("narrations");
        assertEquals(1, got.size());
        assertEquals("吕布", got.get(0).path("subject").asText());
        assertEquals(3.6, got.get(0).path("end_sec").asDouble(), 1e-9);
        assertEquals(1.05, got.get(0).path("speed").asDouble(), 1e-9);
        // 深拷贝：改新方案不应影响上一版
        ((ObjectNode) got.get(0)).put("text", "改过了");
        assertEquals("谁敢与我决一死战！",
                prev.path("shots").get(1).path("narrations").get(0).path("text").asText());
    }

    @Test
    void carriesOverByShotNoEvenWhenShotCountChanges() {
        ObjectNode prev = plan(1, 2, 3);
        shotOf(prev, 2).put("narration", "第三镜的旁白");   // 上一版第 3 镜有旁白
        ObjectNode now = plan(1, 3);                       // 新一版少了第 2 镜 → 下标错位

        DirectorService.mergePrevMeta(now, prev);

        assertEquals("第三镜的旁白", shotOf(now, 1).path("narration").asText());
    }

    @Test
    void doesNotOverwriteNewPlanNarration() {
        ObjectNode prev = plan(1);
        shotOf(prev, 0).put("narration", "旧旁白");
        ObjectNode now = plan(1);
        shotOf(now, 0).put("narration", "新的旁白");

        DirectorService.mergePrevMeta(now, prev);

        assertEquals("新的旁白", shotOf(now, 0).path("narration").asText());
    }

    @Test
    void doesNotOverwriteNewNarrationsWhenPresent() {
        ObjectNode prev = plan(1);
        shotOf(prev, 0).putArray("narrations").addObject().put("text", "旧的");
        ObjectNode now = plan(1);
        shotOf(now, 0).putArray("narrations").addObject().put("text", "新的");

        DirectorService.mergePrevMeta(now, prev);

        assertEquals("新的", shotOf(now, 0).path("narrations").get(0).path("text").asText());
    }

    @Test
    void carriesOverAudioUserFields() {
        ObjectNode prev = plan(1);
        ObjectNode pa = prev.putObject("audio");
        pa.put("voice", "clone:guanyu");
        pa.put("music_mood", "史诗磅礴");
        pa.putArray("voicePresets").addObject().put("id", "guanyu").put("assetId", "a1");
        pa.putArray("voiceBindings").addObject().put("subject", "关羽")
                .put("voice", "clone:guanyu").put("speed", 1);
        pa.putArray("music").addObject().put("start_sec", 0).put("end_sec", 16).put("gain_db", -16.5);

        // 新一版：LLM 只给了 music_mood，其余音频字段全无
        ObjectNode now = plan(1);
        now.putObject("audio").put("music_mood", "温暖治愈");

        DirectorService.mergePrevAudio(now, prev);

        JsonNode a = now.path("audio");
        assertEquals("clone:guanyu", a.path("voice").asText(), "音色要继承");
        assertEquals(1, a.path("voicePresets").size(), "克隆音色库要继承");
        assertEquals(1, a.path("voiceBindings").size(), "角色绑定要继承");
        assertEquals(1, a.path("music").size(), "配乐段要继承");
        // music_mood 是 LLM/用户都可改的，不强行回退
        assertEquals("温暖治愈", a.path("music_mood").asText());
    }

    @Test
    void emptyPrevAudioLeavesPlanUntouched() {
        ObjectNode prev = plan(1);
        prev.putObject("audio");
        ObjectNode now = plan(1);
        now.putObject("audio").put("music_mood", "x");
        DirectorService.mergePrevAudio(now, prev);
        assertFalse(now.path("audio").has("voice"));
        assertFalse(now.path("audio").has("voiceBindings"));
    }

    @Test
    void missingAudioObjectIsCreated() {
        ObjectNode prev = plan(1);
        prev.putObject("audio").put("voice", "中文男");
        ObjectNode now = plan(1);   // 新方案没有 audio
        DirectorService.mergePrevAudio(now, prev);
        assertTrue(now.path("audio").isObject());
        assertEquals("中文男", now.path("audio").path("voice").asText());
    }

    @Test
    void toleratesNullPrev() {
        ObjectNode now = plan(1);
        DirectorService.mergePrevMeta(now, null);
        DirectorService.mergePrevAudio(now, null);
        assertEquals(1, now.path("shots").size());
    }
}
