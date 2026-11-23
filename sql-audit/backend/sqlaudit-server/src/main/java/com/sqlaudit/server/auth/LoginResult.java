package com.sqlaudit.server.auth;

/**
 * 登录成功结果（AuthService → Controller 的契约载体）。
 */
public record LoginResult(String token, UserView user) {
}
