package studio.weaveora.asset;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P8 音频资产查找：把「配音资产 / 配乐资产」还原成带时间信息的 cue。
 *
 * <p>混音（{@code ConcatService}）与导出（{@code ExportService}）共用本类，
 * <b>避免两处各写一套导致口径漂移</b>。
 *
 * <p>核心约定：配音资产必须携带 {@code prompt_snapshot}（= 产生它的 job payload），
 * 里面有 {@code at_sec}（镜内起点）与 {@code line_index}（第几段）。
 * 因为多个配音任务**并发完成、完成顺序不确定**，不能靠 {@code createdAt} 推断顺序。
 * 老资产没有快照 → {@code lineIndex=0 / atSec=0}，退化为「每镜一段」的旧行为。
 */
@Service
public class AudioAssetLookup {

    private final AssetRepository assets;

    public AudioAssetLookup(AssetRepository assets) {
        this.assets = assets;
    }

    /** 一段配音在镜内的位置与窗口（带资产，供导出取 mime/durationMs）。 */
    public record VoiceCue(Asset asset, double atSec, double endSec, int lineIndex,
                           String lineKind, String subject) {
        public String assetKey() {
            return asset.storageKey();
        }

        /**
         * 设了结束点时的裁切时长（秒）；0 = 不裁，用配音自然长度。
         *
         * <p>P13 修正：**不得把音频截到比它本身还短** —— 以前无条件 `atrim` 到窗口，
         * 而窗口来自前端的**字数估算**（如 2.9s），实际音频只有 2.3s/更短或者更长，
         * 结果就是“成片里配音被截断/下一句提早开始”。
         * 现在：若窗口比实际音频**长或相等** → 不裁（音频完整播放）；
         * 只有窗口确实**短于**实际音频时才裁（用户显式把结束点改小了）。
         */
        public double windowSec() {
            if (endSec <= atSec) {
                return 0;
            }
            Integer ms = asset.durationMs();
            if (ms != null && ms > 0) {
                double actual = ms / 1000.0;
                return (endSec - atSec) >= actual - 0.05 ? 0 : (endSec - atSec);
            }
            return endSec - atSec;
        }
    }

    /**
     * 某镜的配音 cue（按镜内起点升序）。同一 {@code line_index} 重复生成时只取最新一条，
     * 避免用户点两次「生成配音」后出现叠音。
     */
    @Transactional(readOnly = true)
    public List<VoiceCue> voiceCues(UUID workspaceId, UUID projectId, int shotNo) {
        List<Asset> vs = assets.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "voice");
        Map<Integer, VoiceCue> latestPerLine = new LinkedHashMap<>();
        for (Asset a : vs) {                       // 已按 createdAt DESC，先遇到的即最新
            JsonNode snap = a.promptSnapshot();
            int li = (snap != null && snap.hasNonNull("line_index")) ? snap.path("line_index").asInt(0) : 0;
            if (latestPerLine.containsKey(li)) {
                continue;
            }
            double at = (snap != null && snap.hasNonNull("at_sec"))
                    ? Math.max(0, snap.path("at_sec").asDouble(0)) : 0;
            double end = (snap != null && snap.hasNonNull("end_sec"))
                    ? snap.path("end_sec").asDouble(0) : 0;
            if (end <= at) {
                end = 0;   // 未设/非法 → 不裁切
            }
            String kind = (snap != null && snap.hasNonNull("line_kind"))
                    ? snap.path("line_kind").asText("narration") : "narration";
            String subject = (snap != null && snap.hasNonNull("subject"))
                    ? snap.path("subject").asText("") : "";
            latestPerLine.put(li, new VoiceCue(a, at, end, li, kind,
                    subject.isBlank() ? null : subject));
        }
        List<VoiceCue> out = new ArrayList<>(latestPerLine.values());
        out.sort(Comparator.comparingDouble(VoiceCue::atSec));
        return out;
    }

    /**
     * 配乐产物按 mood 建索引（同一 mood 多次生成时取最新一条）。
     * mood 来自资产快照（P8 起 bgm job payload 带 {@code mood}）；老资产没有快照 → 归到空串 mood。
     */
    @Transactional(readOnly = true)
    public Map<String, Asset> latestBgmByMood(UUID workspaceId, UUID projectId) {
        List<Asset> bgms = assets.findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, "bgm");
        Map<String, Asset> out = new LinkedHashMap<>();
        for (Asset a : bgms) {                     // 已按 createdAt DESC，先遇到的即最新
            JsonNode snap = a.promptSnapshot();
            String mood = (snap != null && snap.hasNonNull("mood"))
                    ? snap.path("mood").asText("").trim() : "";
            out.putIfAbsent(mood, a);
        }
        return out;
    }
}
