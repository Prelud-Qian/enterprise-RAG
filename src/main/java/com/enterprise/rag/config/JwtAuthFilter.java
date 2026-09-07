package com.enterprise.rag.config;

import com.enterprise.rag.common.LoginUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer <token> 解析用户身份，
 * 放入 SecurityContext，后续业务层通过 SecurityUtil 获取当前用户。
 * token 缺失或非法时不抛异常，由 Security 的 AuthenticationEntryPoint 统一返回 401。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseToken(header.substring(7));
                Long uid = ((Number) claims.get("uid")).longValue();
                String role = claims.get("role", String.class);
                LoginUser user = new LoginUser(uid, claims.getSubject(), role);
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 非法/过期 token：不设置认证信息，后续按匿名处理 → 401
            }
        }
        chain.doFilter(request, response);
    }
}
