package com.enterprise.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户表 sys_user */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 加密后的密码 */
    private String password;
    /** ADMIN / USER */
    private String role;
    /** 1 启用 0 禁用 */
    private Integer enabled;
    private LocalDateTime createdAt;
}
