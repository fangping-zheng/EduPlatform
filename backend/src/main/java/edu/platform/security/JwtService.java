package edu.platform.security;

import edu.platform.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** JWT 签发与解析（jjwt 0.12.x API）。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expire-minutes:720}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole())
                .claim("studentId", user.getStudentId())
                .claim("teacherId", user.getTeacherId())
                .claim("realName", user.getRealName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token；签名错误、过期、格式非法统统返回 {@code null}。
     *
     * <p>过滤器把 null 当作「匿名请求」继续放行，由 Spring Security 决定是否 401，
     * 避免在过滤器里抛异常导致 500。
     */
    public AuthUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Object uid = claims.get("uid");
            Long id = uid instanceof Number n ? n.longValue() : null;
            return new AuthUser(
                    id,
                    claims.getSubject(),
                    claims.get("realName", String.class),
                    claims.get("role", String.class),
                    claims.get("studentId", String.class),
                    claims.get("teacherId", String.class),
                    null,
                    null);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }
}
