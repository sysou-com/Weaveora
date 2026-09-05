package studio.weaveora.director;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 主体分档安全闸（§11.4）：可识别真人 v1.0 才解锁（肖像授权/AI 标识/深度合成合规）。
 * MVP：产品/物体/场景/虚构人物放行；命中“真人分档词”即拦截在导演生成前。
 */
@Component
public class SafetyGuard {

    private final List<String> realPersonWords;

    public SafetyGuard(@Value("${weaveora.safety.real-person-words:}") String words) {
        this.realPersonWords = split(words);
    }

    public Optional<String> matchRealPerson(String rawText) {
        if (rawText == null || realPersonWords.isEmpty()) return Optional.empty();
        String t = rawText.toLowerCase();
        for (String w : realPersonWords) {
            if (t.contains(w.toLowerCase())) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of("真人", "真实人物", "明星", "本人", "自拍", "肖像", "网红",
                    "real person", "actual person", "celebrity", "portrait", "selfie");
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
