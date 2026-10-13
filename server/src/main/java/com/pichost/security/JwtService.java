package com.pichost.security;

import com.pichost.common.BizException;
import com.pichost.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析。
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey secretKey;
    private final long expireMinutes;

    public JwtService(AppProperties appProperties) {
        String secret = appProperties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度必须不少于 32 字节，请通过环境变量 JWT_SECRET 覆盖");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = appProperties.getJwt().getExpireMinutes();
    }

    public String create(AuthUser user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMinutes * 60_000L);
        return Jwts.builder()
                .subject(user.username())
                .claim(CLAIM_UID, user.id())
                .claim(CLAIM_ROLE, user.role())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    public long expireSeconds() {
        return expireMinutes * 60L;
    }

    /**
     * 解析并校验 token，失败返回 null（由过滤器决定后续行为）。
     */
    public AuthUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object uid = claims.get(CLAIM_UID);
            if (uid == null) {
                return null;
            }
            Long userId = Long.valueOf(String.valueOf(uid));
            String role = claims.get(CLAIM_ROLE) == null ? "USER" : String.valueOf(claims.get(CLAIM_ROLE));
            return new AuthUser(userId, claims.getSubject(), role);
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public AuthUser parseOrThrow(String token) {
        AuthUser user = parse(token);
        if (user == null) {
            throw BizException.unauthorized("登录状态已失效，请重新登录");
        }
        return user;
    }
}
