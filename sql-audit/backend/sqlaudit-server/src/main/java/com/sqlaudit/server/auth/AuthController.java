package com.sqlaudit.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证端点（契约对齐 frontend/src/api/index.js 的 auth 段）。
 *
 * <p>{@code POST /api/auth/login} 免鉴权；{@code GET /api/auth/me} 需有效 token
 * （拦截器已把 userId/username/roles 写入 request attribute）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：{username, password} → {token, user:{id, name, displayName, roles}}。 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        LoginResult r = authService.login(request.username(), request.password());
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", r.userId());
        user.put("name", r.username());
        user.put("displayName", r.displayName());
        user.put("roles", r.roles());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", r.token());
        body.put("user", user);
        return body;
    }

    /** 当前登录用户信息（从 request attribute 取）。 */
    @GetMapping("/me")
    @RequireRole
    public Map<String, Object> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        String username = (String) request.getAttribute(RoleAuthInterceptor.ATTR_USERNAME);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", userId);
        body.put("name", username);
        body.put("roles", roles == null ? List.of() : roles);
        return body;
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }
}
