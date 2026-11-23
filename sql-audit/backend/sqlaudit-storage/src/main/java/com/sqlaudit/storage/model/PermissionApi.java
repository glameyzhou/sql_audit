package com.sqlaudit.storage.model;

/**
 * 权限点 ↔ 接口绑定（对应 t_permission_api 表，1:N）。
 *
 * <p>{@code urlPattern} 存的是 <strong>Spring 处理器模式原文</strong>（如
 * {@code /api/tickets/{id}}），不是请求的原始 URI——鉴权时取
 * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} 精确等值查表，
 * 从构造上避免「容器解析」与「安全层解析」不一致导致的绕过（分号参数、%2F、重复斜杠）。</p>
 *
 * <p>{@code httpMethod} 为大写方法名；查表前 HEAD 折叠为 GET（Spring 为 {@code @GetMapping}
 * 自动支持 HEAD）。同一接口可绑多个权限点，语义取并集：<strong>持有任一即放行</strong>。</p>
 */
public class PermissionApi {

    private Long id;
    private Long permissionId;
    /** GET/POST/PUT/DELETE/PATCH（大写） */
    private String httpMethod;
    /** Spring 处理器模式原文，如 /api/tickets/{id} */
    private String urlPattern;

    public PermissionApi() {
    }

    public PermissionApi(Long permissionId, String httpMethod, String urlPattern) {
        this.permissionId = permissionId;
        this.httpMethod = httpMethod;
        this.urlPattern = urlPattern;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(Long permissionId) {
        this.permissionId = permissionId;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    /** 判定用的查表键：{@code "GET /api/tickets/{id}"}（方法大写 + 空格 + 模式原文）。 */
    public String routeKey() {
        return httpMethod + " " + urlPattern;
    }
}
