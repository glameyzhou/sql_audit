package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.UserStatus;

import java.util.List;

/**
 * 当前登录用户（每请求从库解析，写入 request attribute）。
 *
 * <p>JWT 只装身份（userId），角色 / 状态一律以库为准：{@code roleIds} /
 * {@code roleCodes} 由 {@link CurrentUserService} 从 {@code t_user_role} /
 * {@code t_role} 解析，进程内短 TTL 缓存（30s）+ 写操作即时失效——
 * 停用、改角色无需重新登录即生效。</p>
 *
 * <p>拦截器把本对象与「授权本次请求的角色 id 集合」分别写入 request attribute
 * （{@link RoleAuthInterceptor#ATTR_CURRENT_USER} /
 * {@link RoleAuthInterceptor#ATTR_AUTH_ROLES}），服务层按需读取。</p>
 *
 * @param id          用户 id
 * @param username    登录名
 * @param displayName 显示名
 * @param status      账号状态（非 ACTIVE 由拦截器直接 401）
 * @param roleIds     持有的角色 id（按 role id 升序）
 * @param roleCodes   持有的角色 code（与 roleIds 同序）
 */
public record CurrentUser(
        long id,
        String username,
        String displayName,
        UserStatus status,
        List<Long> roleIds,
        List<String> roleCodes) {
}
