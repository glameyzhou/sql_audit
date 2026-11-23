package com.sqlaudit.server.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计事件的操作人快照（契约 §4.6：身份一律取登录态，不再信任请求体）。
 *
 * <p>控制器构造审计事件统一从本记录取字段：actor id / 用户名 / 首个角色 code 取
 * {@link CurrentUser}；{@code clientIp} 取 {@code X-Forwarded-For} 首段（缺省
 * {@code remoteAddr}），{@code userAgent} 取请求头——两者随 {@code t_audit_log}
 * 的规范字段落库。</p>
 *
 * @param id        操作人用户 id
 * @param name      登录名
 * @param roleCode  首个角色 code（无角色时为 {@code null}，落库写 SYSTEM 占位）
 * @param clientIp  客户端 IP（X-Forwarded-For 首段，缺省 remoteAddr）
 * @param userAgent User-Agent 请求头
 */
public record AuditActor(long id, String name, String roleCode, String clientIp, String userAgent) {

    /** 从当前用户 + 请求构造（控制器审计工具的统一入口）。 */
    public static AuditActor of(CurrentUser user, HttpServletRequest request) {
        String roleCode = user.roleCodes().isEmpty() ? null : user.roleCodes().get(0);
        return new AuditActor(user.id(), user.username(), roleCode,
                clientIpOf(request), request.getHeader("User-Agent"));
    }

    /** 取客户端 IP：X-Forwarded-For 首段（多级代理时取第一跳），缺省 remoteAddr。 */
    public static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
