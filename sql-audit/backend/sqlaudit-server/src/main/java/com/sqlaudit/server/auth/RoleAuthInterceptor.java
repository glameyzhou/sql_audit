package com.sqlaudit.server.auth;

import com.sqlaudit.auth.JwtService;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 数据化 RBAC 拦截器（拦截 {@code /api/**}，注册处排除公开端点 {@code POST /api/auth/login}）。
 *
 * <p>判定顺序（契约 §4.2）：</p>
 * <ol>
 *   <li>非 {@link HandlerMethod}（CORS 预检的 {@code PreFlightHandler}、静态资源 / 未映射
 *       请求）→ <strong>显式放行</strong>：预检不带凭据，且这类请求不落在任何 Controller
 *       上，本就不构成业务入口；误拒会让 CORS 预检整站白屏。未映射请求由 DispatcherServlet
 *       直接 404，不进拦截器；</li>
 *   <li>无 token / 签名无效 / 过期，或用户不存在 / 已停用 → 401（JWT 只装身份，
 *       状态与角色每请求从库解析，停用即刻失效）；</li>
 *   <li>取 {@code BEST_MATCHING_PATTERN_ATTRIBUTE}（Spring 选中处理器时写入的最佳匹配模式，
 *       与 Controller 映射同源，从构造上不存在「容器解析 vs 安全层解析」的偏差）+ 方法
 *       （HEAD 折叠为 GET，Spring 为 {@code @GetMapping} 自动支持 HEAD）精确查
 *       {@code t_permission_api}；</li>
 *   <li>无绑定 → 403 + WARN（漏绑端点是裸奔，构建期覆盖测试兜底）；</li>
 *   <li>用户任一角色持有任一绑定权限点，或该权限点 {@code all_users} → 放行，并把
 *       {@link CurrentUser} 与「授权本次请求的角色 id 集合」写入 request attribute
 *       （服务层实例数据范围判定用）；否则 403。</li>
 * </ol>
 */
@Component
public class RoleAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RoleAuthInterceptor.class);

    /** request attribute：当前用户（{@link CurrentUser}）。 */
    public static final String ATTR_CURRENT_USER = "currentUser";
    /** request attribute：授权本次请求的角色 id 集合（{@code Set<Long>}）。 */
    public static final String ATTR_AUTH_ROLES = "authorizingRoleIds";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    public RoleAuthInterceptor(JwtService jwtService, CurrentUserService currentUserService,
                               PermissionService permissionService) {
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 非 HandlerMethod 的处理器不参与权限判定（CORS 预检 / 静态资源等）。
        // 必须保留为显式分支：预检请求不带凭据，误拒会让前端整站白屏。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        JwtService.Claims claims = resolveClaims(request);
        if (claims == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
            return false;
        }

        // JWT 只装身份（userId）；状态 / 角色以库为准（短 TTL 缓存 + 写即时失效）
        CurrentUser user = currentUserService.resolve(claims.userId());
        if (user == null || user.status() != UserStatus.ACTIVE) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
            return false;
        }

        String pattern = bestMatchingPattern(request);
        if (pattern == null) {
            // 理论不可达（HandlerMethod 已由映射选出）；防御性拒绝而不是放行
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCode.FORBIDDEN, "无法确定请求对应的接口模式");
            return false;
        }
        String method = request.getMethod();
        // Spring 为 @GetMapping 自动支持 HEAD；查表前折叠，避免 HEAD 不命中 GET 绑定
        if ("HEAD".equalsIgnoreCase(method)) {
            method = "GET";
        }

        PermissionService.Decision decision = permissionService.decide(user, method, pattern);
        if (!decision.anyBinding()) {
            log.warn("端点无权限点绑定（构建期覆盖测试应已拦截）: {} {}", method, pattern);
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCode.FORBIDDEN, "无权限访问");
            return false;
        }
        if (!decision.allUsersBound() && decision.authorizingRoleIds().isEmpty()) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCode.FORBIDDEN, "无权限访问");
            return false;
        }

        request.setAttribute(ATTR_CURRENT_USER, user);
        request.setAttribute(ATTR_AUTH_ROLES, decision.authorizingRoleIds());
        return true;
    }

    /** 取请求里的当前用户（仅拦截器放行后的请求可用）。 */
    public static CurrentUser currentUser(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(ATTR_CURRENT_USER);
    }

    /** 取授权本次请求的角色 id 集合（仅拦截器放行后的请求可用）。 */
    @SuppressWarnings("unchecked")
    public static Set<Long> authorizingRoles(HttpServletRequest request) {
        return (Set<Long>) request.getAttribute(ATTR_AUTH_ROLES);
    }

    private JwtService.Claims resolveClaims(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return jwtService.verify(auth.substring(BEARER_PREFIX.length()));
    }

    /**
     * 取 Spring 选中处理器时的最佳匹配模式（{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}）。
     * PathPattern 模式下该属性可能是 {@code PathPattern} 对象——按 {@code Object} 取再
     * {@code toString()}（其字符串形态即处理器模式原文）。
     */
    private static String bestMatchingPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? null : pattern.toString();
    }

    /** 手拼错误响应体（拦截器阶段不经过统一错误出口）。 */
    private static void writeError(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code.getCode() + "\",\"message\":\"" + message + "\"}");
    }
}
