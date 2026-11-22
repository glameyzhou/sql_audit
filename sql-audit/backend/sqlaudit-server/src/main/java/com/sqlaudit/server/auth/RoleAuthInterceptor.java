package com.sqlaudit.server.auth;

import com.sqlaudit.auth.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权 + RBAC 拦截器（拦截 {@code /api/**}，放行 {@code /api/auth/login}）。
 *
 * <p>对每个请求：解析 {@code Authorization: Bearer <token>} → {@link JwtService#verify}，
 * 命中则把 userId/username/roles 写入 request attribute；只有标注了 {@link RequireRole}
 * 的端点才强制鉴权——无有效 token 返回 401，角色不符返回 403。未标注的端点不受影响
 * （兼容既有的免鉴权提交/审批/查询链路）。</p>
 */
@Component
public class RoleAuthInterceptor implements HandlerInterceptor {

    /** request attribute：当前用户 id。 */
    public static final String ATTR_USER_ID = "currentUserId";
    /** request attribute：当前用户名。 */
    public static final String ATTR_USERNAME = "currentUsername";
    /** request attribute：当前用户角色 code 列表。 */
    public static final String ATTR_ROLES = "currentRoles";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public RoleAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        JwtService.Claims claims = resolveClaims(request);
        if (claims != null) {
            request.setAttribute(ATTR_USER_ID, claims.userId());
            request.setAttribute(ATTR_USERNAME, claims.username());
            request.setAttribute(ATTR_ROLES, claims.roles());
        }

        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole requireRole = method.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = method.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true; // 非受保护端点
        }

        if (claims == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "未登录或 token 无效");
            return false;
        }
        String[] required = requireRole.value();
        if (required.length > 0 && !hasAnyRole(claims, required)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权限访问");
            return false;
        }
        return true;
    }

    private JwtService.Claims resolveClaims(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return jwtService.verify(auth.substring(BEARER_PREFIX.length()));
    }

    private static boolean hasAnyRole(JwtService.Claims claims, String[] required) {
        for (String role : required) {
            if (claims.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
