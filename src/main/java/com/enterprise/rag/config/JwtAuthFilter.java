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

    /**
     * 每个请求进入过滤器链时都会执行一次——带了合法 JWT 就把用户身份放进 SecurityContext（后续代码能拿到"当前用户"），
     * 没带或非法就静默跳过，最后无论哪种情况都放行给后面的过滤器和接口。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 从请求头取 Authorization，即前端发的 Bearer eyJhbGci...
        String header = request.getHeader("Authorization");
        // 有头、且以 Bearer  开头才进校验逻辑；不满足就直接跳到方法末尾放行
        if (header != null && header.startsWith("Bearer ")) {
            try {
                // substring(7) 切掉 "Bearer " 这 7 个字符，剩纯 token；parseToken 验签 + 校验过期，失败会抛异常，直接落进 catch。
                Claims claims = jwtUtil.parseToken(header.substring(7));
                // 从 payload 取 uid。先转 Number 是因为 JSON 反序列化出来的整数可能是 Integer，统一转 long。
                Long uid = ((Number) claims.get("uid")).longValue();
                // 取 role；getSubject() 取的是 createToken 里 .subject(username) 存进去的用户名。三样凑成 LoginUser。
                String role = claims.get("role", String.class);
                LoginUser user = new LoginUser(uid, claims.getSubject(), role);
                /**
                 * UsernamePasswordAuthenticationToken。这个类有两个构造器：
                 * 两参 (principal, credentials) → 构造出未认证的对象（登录时用的）
                 * 三参 (principal, credentials, authorities) → 构造出已认证的对象
                 * 这里用三参，因为 token 验签通过就等于身份已经确认，不需要再走认证流程。
                 */
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                // SecurityContextHolder 内部是 ThreadLocal：getContext() 取当前线程的 SecurityContext，setAuthentication 把认证对象存进去。
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 非法/过期 token：不设置认证信息，后续按匿名处理 → 401
            }
        }
        // 把请求交给过滤器链的下一环。到这一步，JwtAuthFilter 自己的活干完了（解析 token、存用户身份），剩下的事交给后面的过滤器和最终的业务代码。
        chain.doFilter(request, response);
    }
}
