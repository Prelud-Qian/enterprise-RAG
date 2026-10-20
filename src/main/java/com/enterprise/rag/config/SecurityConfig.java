package com.enterprise.rag.config;

import com.enterprise.rag.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 无状态 JWT 配置
 * 权限模型：
 * - /api/auth/**  公开（注册/登录）
 * - /api/admin/** 仅 ADMIN
 * - 其余接口需登录；知识库级别的数据隔离在 Service 层按 owner_id 校验
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AuthorizationFilter 是 Spring Security 过滤器链里的“查表门卫”，它专门负责执行你在 authorizeHttpRequests 里写下的那些授权规则，根据请求的 URL 路径和 SecurityContext 里的用户身份，决定这个请求是放行还是拦截。
     *
     * 你写的规则本质上就是一张“路径 → 谁能进”的表，这张表由你自己定义，匹配顺序是从上往下，一旦匹配到就停止，所以顺序很重要。
     *
     * 这张规则表在 Spring 启动时只构建一次，配置方法执行一次后，就把规则编译成一个 SecurityFilterChain 对象放进容器，之后这个方法永远不会再执行，所以你改了规则必须重启服务才会生效。
     *
     * 每当一个请求进来，AuthorizationFilter 就会查一次表，拿请求路径去比对规则，再结合 SecurityContext 里的身份判断放不放行，一旦放行进入业务代码，业务内部就不会再查这张表了。
     *
     * 而 SecurityContext 里的身份和权限，并不是 AuthorizationFilter 自己去解析 JWT 得到的，而是由 JwtAuthFilter 提前解析 JWT，把用户和权限放进去的，AuthorizationFilter 只负责从里面读取权限，再拿它去比对规则表。
     *
     * 所以整条线就是：启动时建一次规则表，每个请求由 JwtAuthFilter 先认出你是谁并放进 SecurityContext，再由 AuthorizationFilter 查一次表决定放不放行，进了业务就不再查表，而方法级的权限控制则靠注解另外处理。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR 是容器回派（SseEmitter 完成后触发），JwtAuthFilter 不处理异步派发，
                        // 不放行会因取不到 SecurityContext 而抛 AccessDenied，此时响应已提交只能刷日志
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        // 前端静态资源放行：Vue3 + Element Plus 由 Spring Boot 直接托管，未登录也要能打开页面。
                        // 只放行静态路径，/api/** 仍受 anyRequest().authenticated() 保护
                        .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/lib/**").permitAll()
                        // Swagger 文档放行
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 可观测性：健康检查公开，指标仅 ADMIN
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeJson(res, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeJson(res, 403, "无权限访问")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 认证/授权失败时输出统一 Result JSON，而不是默认的空白 401/403 */
    private void writeJson(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(code, msg));
    }
}
