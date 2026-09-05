package studio.weaveora.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 邮箱/手机验证码（§22 硬性指令 14：一律 Redis，禁止 ConcurrentHashMap）。
 * key: weaveora:{env}:code:{account}；发送频控 60s；TTL 5 分钟。
 */
@Service
public class CodeService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final String prefix;

    public CodeService(StringRedisTemplate redis, @Value("${weaveora.env:test}") String env) {
        this.redis = redis;
        this.prefix = "weaveora:" + env + ":";
    }

    public String codeKey(String account) {
        return prefix + "code:" + account.toLowerCase().trim();
    }

    private String freqKey(String account) {
        return codeKey(account) + ":last";
    }

    /** 生成并"发送"（MVP 直接返回便于联调；生产接邮件/短信网关），带 60s 频控。 */
    public String issue(String account) {
        String freqKey = freqKey(account);
        Boolean ok = redis.opsForValue().setIfAbsent(freqKey, Long.toString(System.currentTimeMillis()),
                SEND_INTERVAL);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BizException(ErrorCode.EMAIL_CODE_SEND_TOO_FREQUENT, "发送过于频繁，请 60 秒后再试");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForValue().set(codeKey(account), code, CODE_TTL);
        return code;
    }

    public void verify(String account, String code) {
        String key = codeKey(account);
        String saved = redis.opsForValue().get(key);
        if (saved == null) {
            throw new BizException(ErrorCode.EMAIL_CODE_EXPIRED, "验证码不存在或已过期");
        }
        if (!saved.equals(code)) {
            throw new BizException(ErrorCode.EMAIL_CODE_INVALID, "验证码错误");
        }
        redis.delete(key);
    }
}
