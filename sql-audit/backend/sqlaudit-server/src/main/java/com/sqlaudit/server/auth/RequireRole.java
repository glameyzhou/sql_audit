package com.sqlaudit.server.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点级 RBAC 标注：声明访问该端点所需的角色 code。
 *
 * <p>{@code value} 为空表示「任意已登录用户」即可（仅需有效 token，不限定角色）；
 * 指定一个或多个 code 表示「满足其一即可」。校验由 {@link RoleAuthInterceptor} 执行。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {

    /** 所需角色 code（DEV/LEAD/DBA/ADMIN）；空数组 = 任意已登录用户。 */
    String[] value() default {};
}
