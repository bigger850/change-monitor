package com.biggerink.watch.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证主体（作为 Authentication.principal，不实现 UserDetails，密码不进内存）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {
    private Long userId;
    private String username;
    private String role;   // USER / ADMIN
    private String email;
}
