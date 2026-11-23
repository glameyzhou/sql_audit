package com.sqlaudit.server.auth;

import java.util.List;

/**
 * 用户视图（{@code POST /api/auth/login} 与 {@code GET /api/auth/me} 的返回形态，
 * 契约 §6 认证段）。
 *
 * <p>{@code apis} = 用户经角色持有的权限点所绑定接口 ∪ 全部 {@code all_users} 权限点的
 * 接口，去重排序（由 {@link PermissionService#listApis} 产出）。前端菜单 / 路由守卫 /
 * 按钮显隐一律按它判断（{@code can('METHOD /pattern')}），后端仍是唯一权威。</p>
 *
 * @param id          用户 id
 * @param username    登录名
 * @param displayName 显示名
 * @param roles       持有的角色（code + 名称，按 role id 升序）
 * @param apis        可调用的接口集合（"METHOD /pattern" 字符串，排序去重）
 */
public record UserView(long id, String username, String displayName,
                       List<RoleRef> roles, List<String> apis) {

    /** 角色视图。 */
    public record RoleRef(String code, String name) {
    }
}
