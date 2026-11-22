package com.sqlaudit.server.auth;

import java.util.List;

/**
 * 登录成功结果（AuthService → Controller 的契约载体）。
 */
public record LoginResult(
        String token,
        long userId,
        String username,
        String displayName,
        List<String> roles) {
}
