package studio.weaveora.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 无状态 JWT（参考 MirrorTalk JwtUtil 思路重写，不拷贝其 User 实体）。
 * access 15m + refresh 14d，旋转 refresh（§15.1）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtUtil(@Value("${weaveora.security.jwt.secret}") String secret,
                   @Value("${weaveora.security.jwt.access-ttl}") Duration accessTtl,
                   @Value("${weaveora.security.jwt.refresh-ttl}") Duration refreshTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String generateAccessToken(UUID userId) {
        return build(userId, "access", accessTtl);
    }

    public String generateRefreshToken(UUID userId) {
        return build(userId, "refresh", refreshTtl);
    }

    private String build(UUID userId, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of("type", type))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名与类型；无效抛 JwtException。 */
    public Claims parse(String token, String expectedType) throws JwtException {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        if (expectedType != null && !expectedType.equals(claims.get("type", String.class))) {
            throw new JwtException("token type mismatch");
        }
        return claims;
    }

    public UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
