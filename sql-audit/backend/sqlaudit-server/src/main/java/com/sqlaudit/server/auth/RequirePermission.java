package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.PermissionCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点级 RBAC 标注：声明访问该端点所需的权限点。
 *
 * <p>与 {@link RequireRole} 的区别：{@code @RequirePermission} 的判定走「界面配置优先、
 * 代码兜底」——权限点在 {@code t_role_permission} 被配置过（{@code customized=true}）则
 * 按配置角色判定，否则回落到 {@link PermissionCode#getDefaultRoles()}（即原
 * {@code @RequireRole} 写死的默认角色）。校验由 {@link RoleAuthInterceptor} 执行。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    /** 所需权限点。 */
    PermissionCode value();
}
