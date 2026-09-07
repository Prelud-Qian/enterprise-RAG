package com.enterprise.rag.entity.vo;

import com.enterprise.rag.entity.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String role;
    private Integer enabled;
    private LocalDateTime createdAt;

    /** 不暴露 password 字段 */
    public static UserVO from(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getRole(),
                user.getEnabled(), user.getCreatedAt());
    }
}
