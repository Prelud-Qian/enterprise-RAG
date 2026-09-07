package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.JwtUtil;
import com.enterprise.rag.dao.mapper.UserMapper;
import com.enterprise.rag.entity.SysUser;
import com.enterprise.rag.entity.dto.LoginRequest;
import com.enterprise.rag.entity.dto.RegisterRequest;
import com.enterprise.rag.entity.vo.LoginResponse;
import com.enterprise.rag.entity.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 注册/登录：注册默认 USER 角色，密码 BCrypt 加密入库；
 * 登录成功签发 JWT，后续请求由 JwtAuthFilter 校验
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public Long register(RegisterRequest req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole("USER");
        user.setEnabled(1);
        userMapper.insert(user);
        return user.getId();
    }

    public LoginResponse login(LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, UserVO.from(user));
    }
}
