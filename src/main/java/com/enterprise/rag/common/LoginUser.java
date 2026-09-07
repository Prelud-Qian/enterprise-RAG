package com.enterprise.rag.common;

/**
 * 当前登录用户信息，由 JwtAuthFilter 校验 JWT 后放入 SecurityContext，
 * 业务层通过 {@link com.enterprise.rag.util.SecurityUtil} 获取
 */
public record LoginUser(Long id, String username, String role) {
}
