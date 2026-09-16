package studio.weaveora.infra.llm;

/**
 * JSON 兜底修复（OpenAI-Compat 网关用）。
 *
 * <p>为什么需要：模型在生成长中文段落时，会在 JSON 字符串里直接写出**真实换行/制表符**，
 * 于是返回体看起来是 JSON、实际非法：
 * <pre>
 * {"value": "第一段
 * 第二段"}          ← 非法：字符串里不能有真实换行
 * </pre>
 * 实测（2026-09-17，剧本字段/分集生成）：`readTree` 报
 * {@code Unexpected character ('豪'): was expecting comma to separate Object entries}，
 * 重试两次仍然失败 → 整个请求 422。这属于**表达瑕疵而非内容错误**，不该让用户重来。
 *
 * <p>做法：单遍扫描，只在「字符串字面量内部」把裸控制字符转义（换行/制表符 → 转义序列，其余控制字符 → U+00xx 转义）；
 * 字符串外的换行（合法缩进）原样保留；已转义的 {@code \" \\ \n} 不动。
 *
 * <p>保守性：本函数只做转义修复，**不会**让非法 JSON 变成"另一种含义"，也不做字段增删；
 * 若模型真的写坏了结构（少逗号/少花括号），仍然会解析失败并走原有重试与报错路径。
 */
public final class JsonGuard {

    private JsonGuard() {
    }

    /** 修复 JSON 字符串字面量内的裸控制字符；输入为 null 时返回 null。 */
    public static String repairStrings(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 64);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                }
                continue;
            }
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                out.append(c);
                inString = false;
                continue;
            }
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> { /* 归一化：丢弃 CR，避免出现 \r\n */ }
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
