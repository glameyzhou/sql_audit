package com.sqlaudit.server.auth;

/**
 * 登录认证失败（用户名/密码错误、账号停用）——统一映射为 HTTP 401。
 *
 * <p>与业务异常区分：业务异常走 400，认证失败必须 401（前端据此跳登录页）。
 * 由 {@code GlobalExceptionHandler} 兜底转为 401 响应。</p>
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}
