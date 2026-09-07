package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.dto.LoginRequest;
import com.enterprise.rag.entity.dto.RegisterRequest;
import com.enterprise.rag.entity.vo.LoginResponse;
import com.enterprise.rag.entity.vo.UserVO;
import com.enterprise.rag.service.AuthService;
import com.enterprise.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（注册 / 登录 / 当前用户）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        // 当前用户信息由 JwtAuthFilter 从 token 解析，这里演示 SecurityUtil 用法
        return Result.ok(new UserVO(SecurityUtil.currentUser().id(),
                SecurityUtil.currentUser().username(), SecurityUtil.currentUser().role(),
                1, null));
    }
}
