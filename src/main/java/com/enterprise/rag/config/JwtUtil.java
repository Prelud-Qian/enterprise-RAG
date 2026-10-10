package com.enterprise.rag.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验（jjwt 0.12 API）
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireHours;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours:24}") long expireHours) {
        // HS256 要求密钥至少 32 字节
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = expireHours;
    }

    /**
     * 把用户三要素（id、用户名、角色）签成一个 JWT 字符串返回——token 里带着身份信息，且带签名防篡改；
     * 入参就是"要装进 token 的三样东西"，返回值是最终字符串。
     */
    public String createToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key)
                .compact();
    }

    /** 校验并解析，token 非法或过期抛 JwtException */
    /**
     * 拿一个 JWT 字符串，验证签名，然后取出里面的 claims（载荷数据）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()    // 1. 创建 JWT 解析器
                .verifyWith(key)    // 2. 把签名密钥交给它 ← 校验用的就是这把 key
                .build()    // 3. 构建解析器实例
                .parseSignedClaims(token)   // 4. 解析 + 验签，这一步就是"校验有没有被改过"
                .getPayload();  // 5. 取出 payload（Claims）
    }
}
