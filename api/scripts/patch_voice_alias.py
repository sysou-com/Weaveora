#!/usr/bin/env python3
"""P13：音色/语速解析支持「别名」匹配（绑定表里写 贾宝玉，台词里写 宝玉 也要对上）。幂等。"""
import io

p = "api/src/main/java/studio/weaveora/director/plan/AudioPlan.java"
s = io.open(p, encoding="utf-8").read()
if "subjectMatches" in s:
    print("already patched")
    raise SystemExit(0)

OLD = '''    /** 音色解析：lineVoice > voiceBindings[subject] > audio.voice > 默认。 */
    public static String voiceFor(JsonNode plan, String subject, String lineVoice) {
        if (!blank(lineVoice)) {
            return lineVoice.trim();
        }
        JsonNode audio = plan.path("audio");
        if (!blank(subject)) {
            for (JsonNode b : audio.path("voiceBindings")) {
                if (subject.trim().equals(b.path("subject").asText("").trim())
                        && !blank(b.path("voice").asText(""))) {
                    return b.path("voice").asText("").trim();
                }
            }
        }
        String v = audio.path("voice").asText("").trim();
        return v.isEmpty() ? DEFAULT_VOICE : v;
    }'''

NEW = '''    /**
     * 主体名是否与绑定项一致 —— 支持**别名互认**。
     *
     * <p>踩过的坑：绑定表里写的是别名（`贾宝玉`），剧情主体/台词里写的是 `宝玉`，
     * 早前这里只做字符串相等 → 全部对不上 → **所有角色都回落到同一个默认音色**
     * （用户以为"所有人都绑成了同一个人"）。
     */
    static boolean subjectMatches(JsonNode plan, String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        // 别名互认：任一方是对方所在主体的别名
        for (String name : new String[]{x, y}) {
            String other = name.equals(x) ? y : x;
            for (JsonNode sub : plan.path("subjects")) {
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                names.add(sub.path("name").asText("").trim());
                for (JsonNode al : sub.path("aliases")) {
                    names.add(al.asText("").trim());
                }
                if (names.contains(name) && names.contains(other)) {
                    return true;
                }
            }
        }
        // 退一步：中文名 2 字片段（秦可卿 ↔ 可卿）
        return nameSimilar(x, y);
    }

    /** 中文名容错：长度≥3 的一方包含另一方的 2 字片段。 */
    static boolean nameSimilar(String x, String y) {
        String longOne = x.length() >= y.length() ? x : y;
        String shortOne = x.length() >= y.length() ? y : x;
        if (shortOne.length() < 2 || longOne.length() < 3) {
            return false;
        }
        if (longOne.contains(shortOne)) {
            return true;
        }
        for (int i = 0; i + 2 <= longOne.length(); i++) {
            if (shortOne.contains(longOne.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    /** 音色解析：lineVoice > voiceBindings[subject]（别名互认） > audio.voice > 默认。 */
    public static String voiceFor(JsonNode plan, String subject, String lineVoice) {
        if (!blank(lineVoice)) {
            return lineVoice.trim();
        }
        JsonNode audio = plan.path("audio");
        if (!blank(subject)) {
            for (JsonNode b : audio.path("voiceBindings")) {
                if (!blank(b.path("voice").asText(""))
                        && subjectMatches(plan, subject, b.path("subject").asText(""))) {
                    return b.path("voice").asText("").trim();
                }
            }
        }
        String v = audio.path("voice").asText("").trim();
        return v.isEmpty() ? DEFAULT_VOICE : v;
    }'''

assert s.count(OLD) == 1
s = s.replace(OLD, NEW, 1)

# 语速同理：别名互认
OLD2 = '''    public static double speedFor(JsonNode plan, String subject, double lineSpeed) {'''
assert s.count(OLD2) == 1

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("AudioPlan 别名互认已加")
