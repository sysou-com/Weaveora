package studio.weaveora.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.time.Duration;
import java.time.LocalDate;

/**
 * §22.2 简化额度（MVP 默认）：无 wallet/ledger，全免费，仅做每日配额/限频（Redis 计数）。
 * - director/generate：每日 N 次/用户（默认 20）
 * - still 任务：每日 M 次/用户（默认 200）
 * - clip 运动秒数：每日 S 秒/用户（默认 600）
 * Redis 不可用时 fail-open（记日志放行），避免影响出图主链路。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final Duration DAY = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final boolean enabled;   // 仅 simplified 生效
    private final int maxDirectorDay;
    private final int maxStillsDay;
    private final int maxClipSecondsDay;

    public QuotaService(StringRedisTemplate redis,
                        @Value("${weaveora.billing.mode:simplified}") String mode,
                        @Value("${weaveora.billing.simplified.max-director-day:20}") int maxDirectorDay,
                        @Value("${weaveora.billing.simplified.max-stills-day:200}") int maxStillsDay,
                        @Value("${weaveora.billing.simplified.max-clip-seconds-day:600}") int maxClipSecondsDay) {
        this.redis = redis;
        this.enabled = "simplified".equalsIgnoreCase(mode);
        this.maxDirectorDay = maxDirectorDay;
        this.maxStillsDay = maxStillsDay;
        this.maxClipSecondsDay = maxClipSecondsDay;
    }

    public void checkDirector(java.util.UUID userId) {
        if (!enabled) return;
        Long v = incr("director", userId.toString(), 1);
        if (v != null && v > maxDirectorDay) {
            throw new BizException(ErrorCode.RATE_LIMITED, "今日导演次数已达上限（" + maxDirectorDay + "），明日再试（§22.2）");
        }
    }

    public void checkStills(java.util.UUID userId, int count) {
        if (!enabled || count <= 0) return;
        Long v = incr("stills", userId.toString(), count);
        if (v != null && v > maxStillsDay) {
            throw new BizException(ErrorCode.INSUFFICIENT_CREDITS,
                    "今日静帧配额不足（上限 " + maxStillsDay + " 张）");
        }
    }

    public void checkClipSeconds(java.util.UUID userId, int seconds) {
        if (!enabled || seconds <= 0) return;
        Long v = incr("clipsec", userId.toString(), seconds);
        if (v != null && v > maxClipSecondsDay) {
            throw new BizException(ErrorCode.INSUFFICIENT_CREDITS,
                    "今日运动配额不足（上限 " + maxClipSecondsDay + " 秒）");
        }
    }

    private Long incr(String kind, String userId, int amount) {
        try {
            String key = "weaveora:" + env() + ":quota:" + kind + ":" + userId + ":" + LocalDate.now();
            Long v = redis.opsForValue().increment(key, amount);
            if (v != null && v.longValue() == amount) {
                redis.expire(key, DAY);
            }
            return v;
        } catch (Exception e) {
            log.warn("quota redis 不可用（放行）: {}", e.getMessage());
            return null;
        }
    }

    private String env() {
        try {
            return System.getenv().getOrDefault("WEAVEORA_ENV", "test");
        } catch (Exception e) {
            return "test";
        }
    }
}
