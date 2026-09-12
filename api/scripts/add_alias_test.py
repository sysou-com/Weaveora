import io

p = "api/src/test/java/studio/weaveora/director/plan/AudioPlanTest.java"
s = io.open(p, encoding="utf-8").read()
if "voiceBindingMatchesAlias" in s:
    print("already")
    raise SystemExit(0)

TEST = '''
    @Test
    void voiceBindingMatchesAlias() throws Exception {
        // 绑定表写别名「贾宝玉」，台词 subject 是「宝玉」→ 必须解析到它绑定的音色。
        // 踩过的坑：早期这里只做字符串相等 → 全部对不上 → 所有角色都用同一个默认音色。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"audio":{"voice":"中文女",
                  "voiceBindings":[{"subject":"贾宝玉","voice":"clone:baoyu"},{"subject":"警幻","voice":"英文女"}],
                  "voicePresets":[{"id":"baoyu","name":"宝玉","assetId":"11111111-1111-1111-1111-111111111111","promptText":"你好","durationSec":3}]},
                 "subjects":[{"name":"宝玉","aliases":["贾宝玉","宝二爷"]},{"name":"警幻","aliases":["警幻仙姑"]}]}
                """);
        assertEquals("clone:baoyu", AudioPlan.voiceFor(plan, "宝玉", null), "别名绑定要生效");
        assertEquals("clone:baoyu", AudioPlan.voiceFor(plan, "宝二爷", null), "别名本身也要生效");
        assertEquals("英文女", AudioPlan.voiceFor(plan, "警幻仙姑", null));
        assertEquals("中文女", AudioPlan.voiceFor(plan, "贾政", null), "不认识的角色回落默认音色");
    }
'''
io.open(p, "w", encoding="utf-8", newline="\n").write(s.rstrip() + "\n" + TEST)
print("test added")
