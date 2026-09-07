package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.rag.dao.mapper.UserMapper;
import com.enterprise.rag.entity.SysUser;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.entity.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户管理（仅 ADMIN 可调用的用户列表）
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    public PageVO<UserVO> page(long page, long size) {
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getCreatedAt));
        Page<UserVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(UserVO::from).toList());
        return PageVO.of(voPage);
    }
}
