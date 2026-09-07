package com.enterprise.rag.util;

import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.common.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具：从 SecurityContext 取 JwtAuthFilter 放入的 LoginUser
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user;
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(currentUser().role());
    }
}
