package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.entity.vo.UserVO;
import com.enterprise.rag.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员接口：SecurityConfig 中 /api/admin/** 仅 ADMIN 角色可访问，
 * 用普通用户 token 调此接口会得到 403（RBAC 验证场景）
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    @GetMapping("/users")
    public Result<PageVO<UserVO>> users(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(userService.page(page, size));
    }
}
