package com.sqlaudit.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点（契约 §6 认证段）。
 *
 * <p>{@code POST /api/auth/login} 免鉴权（拦截器注册处排除，公开白名单唯一成员）；
 * {@code GET /api/auth/me} 走 {@code basic}（all_users）权限点，返回
 * {@link UserView}（含可调用接口集合 {@code apis}）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：{username, password} → {token, user: UserView}。 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        LoginResult r = authService.login(request.username(), request.password());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", r.token());
        body.put("user", r.user());
        return body;
    }

    /** 当前登录用户（拦截器已把 CurrentUser 写入 request attribute）。 */
    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        return authService.viewOf(user);
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }
}
