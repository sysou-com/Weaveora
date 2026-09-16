package studio.weaveora.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonGuard} 契约：修复「JSON 字符串里的裸换行」。
 *
 * <p>为什么值得测：2026-09-17 实测——模型写长中文段落时会在字符串里直出真实换行，
 * 网关 `readTree` 校验失败 → 重试两次仍失败 → 用户拿到 422（内容其实是对的）。
 * 这个修复器是那条链路的唯一保险，必须「修好合法意图」且「不破坏合法 JSON」。
 */
class JsonGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void repairsBareNewlineInsideString() throws Exception {
        String broken = "{\"value\": \"第一段\n第二段\"}";
        assertThrowsParse(broken);
        JsonNode fixed = MAPPER.readTree(JsonGuard.repairStrings(broken));
        assertEquals("第一段\n第二段", fixed.path("value").asText());
    }

    @Test
    void repairsBareCrLfAndTab() throws Exception {
        String broken = "{\"content\": \"甲\r\n\t乙\"}";
        JsonNode fixed = MAPPER.readTree(JsonGuard.repairStrings(broken));
        assertEquals("甲\n\t乙", fixed.path("content").asText());
    }

    @Test
    void keepsPrettyPrintedJsonIntact() throws Exception {
        String pretty = """
                {
                  "value": "正常内容",
                  "note": "多行缩进"
                }
                """;
        assertEquals(pretty, JsonGuard.repairStrings(pretty));
        assertEquals("正常内容", MAPPER.readTree(JsonGuard.repairStrings(pretty)).path("value").asText());
    }

    @Test
    void doesNotDoubleEscapeAlreadyEscapedSequences() throws Exception {
        String ok = "{\"value\": \"第一行\\n第二行\\\"带引号\\\"\"}";
        String out = JsonGuard.repairStrings(ok);
        assertEquals(ok, out);
        assertEquals("第一行\n第二行\"带引号\"", MAPPER.readTree(out).path("value").asText());
    }

    @Test
    void repairsLongMultiParagraphPayload() throws Exception {
        // 模拟真实失败态：4 段中文，字符串内全是裸换行
        String body = "开端：雨夜。\n发展：旧报纸。\n转折：名字。\n高潮：闸口。";
        String broken = "{\"field\":\"story\",\"value\":\"" + body + "\",\"note\":\"4 段\"}";
        assertThrowsParse(broken);
        JsonNode fixed = MAPPER.readTree(JsonGuard.repairStrings(broken));
        assertEquals(body, fixed.path("value").asText());
        assertEquals("4 段", fixed.path("note").asText());
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, JsonGuard.repairStrings(null));
        assertEquals("", JsonGuard.repairStrings(""));
    }

    @Test
    void stillRejectsStructurallyBrokenJson() {
        // 缺右花括号：修复器不该把它变成合法 JSON（否则就是掩盖真问题）
        String broken = "{\"value\": \"内容\"";
        assertFalse(isValid(JsonGuard.repairStrings(broken)));
    }

    private static void assertThrowsParse(String raw) {
        assertFalse(isValid(raw), "样本本身应当是非法 JSON: " + raw);
    }

    private static boolean isValid(String raw) {
        try {
            MAPPER.readTree(raw);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void repairIsIdempotent() {
        String broken = "{\"value\": \"甲\n乙\"}";
        String once = JsonGuard.repairStrings(broken);
        assertEquals(once, JsonGuard.repairStrings(once));
        assertTrue(once.contains("\\n"));
    }
}
