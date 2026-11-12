package com.biggerink.watch.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * JWT 生成与校验（jjwt 0.12.5，HS256）。Access 2h / Refresh 7d。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long accessTtlMs;
    private final long refreshTtlMs;
    private final String issuer;

    public JwtUtil(
            @Value("${watch.jwt.secret}") String secret,
            @Value("${watch.jwt.access-ttl-ms:7200000}") long accessTtlMs,
            @Value("${watch.jwt.refresh-ttl-ms:604800000}") long refreshTtlMs,
            @Value("${watch.jwt.issuer:bigger.ink}") String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMs = accessTtlMs;
        this.refreshTtlMs = refreshTtlMs;
        this.issuer = issuer;
    }

    public String generateAccess(LoginUser user) {
        return build(user, "access", accessTtlMs);
    }

    public String generateRefresh(LoginUser user) {
        return build(user, "refresh", refreshTtlMs);
    }

    public long getAccessTtlMs() {
        return accessTtlMs;
    }

    public long getRefreshTtlMs() {
        return refreshTtlMs;
    }

    private String build(LoginUser user, String type, long ttlMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getUsername())
                .claim("uid", user.getUserId())
                .claim("role", user.getRole())
                .claim("typ", type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMs))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isRefresh(Claims claims) {
        return "refresh".equals(claims.get("typ", String.class));
    }

    public LoginUser toLoginUser(Claims claims) {
        LoginUser user = new LoginUser();
        user.setUsername(claims.getSubject());
        Object role = claims.get("role");
        user.setRole(role == null ? "USER" : String.valueOf(role));
        Object uid = claims.get("uid");
        if (uid instanceof Number n) {
            user.setUserId(n.longValue());
        }
        return user;
    }

    /** token 的 SHA-256 指纹，用于注销黑名单（不把完整 token 写入 Redis）。 */
    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
