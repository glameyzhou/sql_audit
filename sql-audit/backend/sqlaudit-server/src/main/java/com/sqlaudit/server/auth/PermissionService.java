package com.sqlaudit.server.auth;

import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.PermissionApi;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 权限判定服务（完全数据化 RBAC 的判定核心）。
 *
 * <p>判定输入是「HTTP 方法 + Spring 处理器模式原文」（如 {@code GET /api/tickets/{id}}），
 * 由拦截器从 {@code BEST_MATCHING_PATTERN_ATTRIBUTE} 取得后<strong>精确等值查表</strong>——
 * 不匹配原始 URI（无 Spring Security 防火墙，规避分号 / {@code %2F} 类绕过）。</p>
 *
 * <p>数据来自 {@link PermissionDao} 的三张全量快照（一次性加载进内存，RBAC 写操作后
 * {@link #reload()}）：</p>
 * <ul>
 *   <li>「接口 → 权限点集合」：{@code t_permission_api}；</li>
 *   <li>「角色 → 权限点集合」：{@code t_role_permission}；</li>
 *   <li>「all_users 权限点」/「meta 权限点」：{@code t_permission} 标志位。</li>
 * </ul>
 *
 * <p>同一接口可绑多个权限点，语义取并集：<strong>持有任一绑定权限点即放行</strong>；
 * 权限点 {@code all_users} 表示所有登录用户默认拥有（不写授权行）。已映射但没有任何绑定的
 * 端点 → 拦截器 403 + WARN（构建期覆盖测试保证它到不了生产）。</p>
 *
 * <p>代码里没有任何权限码字面量——能力判断一律走
 * {@link #canCall(CurrentUser, String, String)}。</p>
 */
@Service
public class PermissionService {

    private final PermissionDao permissionDao;

    private volatile Snapshot snapshot = Snapshot.empty();

    public PermissionService(PermissionDao permissionDao) {
        this.permissionDao = permissionDao;
        reload();
    }

    /** 全量重载判定快照（启动时构造加载；RBAC 写操作后即时调用）。 */
    public synchronized void reload() {
        Map<Long, Boolean> allUsers = new HashMap<>();
        Map<Long, Boolean> meta = new HashMap<>();
        for (Permission p : permissionDao.listAll()) {
            allUsers.put(p.getId(), p.isAllUsers());
            meta.put(p.getId(), p.isMeta());
        }
        Map<String, Set<Long>> apiToPermissions = new HashMap<>();
        for (PermissionApi api : permissionDao.listAllApis()) {
            apiToPermissions.computeIfAbsent(api.routeKey(), k -> new HashSet<>()).add(api.getPermissionId());
        }
        Map<Long, Set<Long>> roleToPermissions = new HashMap<>();
        for (long[] pair : permissionDao.listRolePermissionPairs()) {
            roleToPermissions.computeIfAbsent(pair[0], k -> new HashSet<>()).add(pair[1]);
        }
        snapshot = new Snapshot(
                immutable(apiToPermissions),
                immutableLong(roleToPermissions),
                Set.copyOf(allUsers.entrySet().stream()
                        .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())),
                Set.copyOf(meta.entrySet().stream()
                        .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())));
    }

    /**
     * 一次鉴权判定。
     *
     * @param user    当前用户（含角色）
     * @param method  HTTP 方法（大写；HEAD 已折叠为 GET）
     * @param pattern Spring 处理器模式原文（{@code BEST_MATCHING_PATTERN}）
     */
    public Decision decide(CurrentUser user, String method, String pattern) {
        Set<Long> bound = snapshot.apiToPermissions().getOrDefault(method + " " + pattern, Set.of());
        if (bound.isEmpty()) {
            return Decision.noBinding();
        }
        Set<Long> authorizing = new LinkedHashSet<>();
        for (long roleId : user.roleIds()) {
            Set<Long> rolePerms = snapshot.roleToPermissions().getOrDefault(roleId, Set.of());
            for (long permId : bound) {
                if (rolePerms.contains(permId)) {
                    authorizing.add(roleId);
                    break;
                }
            }
        }
        boolean allUsers = bound.stream().anyMatch(snapshot.allUsersPermissionIds()::contains);
        return new Decision(true, allUsers, Set.copyOf(authorizing));
    }

    /** 该 (method, pattern) 是否存在权限点绑定（无绑定 = 端点裸奔，拦截器 WARN）。 */
    public boolean isBound(String method, String pattern) {
        return !snapshot.apiToPermissions().getOrDefault(method + " " + pattern, Set.of()).isEmpty();
    }

    /**
     * 能力判断：当前用户能否调用某个接口。模式字符串常量放在对应 Controller 旁
     * （如 {@code TicketController#API_GET_TICKETS_ALL}），与 t_permission_api 的
     * url_pattern 一字不差（覆盖测试钉死）。
     */
    public boolean canCall(CurrentUser user, String method, String pattern) {
        Decision d = decide(user, method, pattern);
        return d.anyBinding() && (d.allUsersBound() || !d.authorizingRoleIds().isEmpty());
    }

    /** 用户是否经任意角色持有某个 meta 权限点（权限收敛判断用，快照口径）。 */
    public boolean holdsMetaPermission(CurrentUser user) {
        Set<Long> meta = snapshot.metaPermissionIds();
        if (meta.isEmpty()) {
            return false;
        }
        for (long roleId : user.roleIds()) {
            Set<Long> rolePerms = snapshot.roleToPermissions().getOrDefault(roleId, Set.of());
            for (long permId : meta) {
                if (rolePerms.contains(permId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 用户可调用的接口集合（{@code /api/auth/me} 的 {@code apis} 字段）：
     * 经角色持有的权限点所绑定接口 ∪ 全部 all_users 权限点的接口，去重排序。
     */
    public List<String> listApis(CurrentUser user) {
        TreeSet<String> apis = new TreeSet<>();
        for (Map.Entry<String, Set<Long>> e : snapshot.apiToPermissions().entrySet()) {
            for (long permId : e.getValue()) {
                if (snapshot.allUsersPermissionIds().contains(permId)) {
                    apis.add(e.getKey());
                    break;
                }
                for (long roleId : user.roleIds()) {
                    if (snapshot.roleToPermissions().getOrDefault(roleId, Set.of()).contains(permId)) {
                        apis.add(e.getKey());
                        break;
                    }
                }
            }
        }
        return List.copyOf(apis);
    }

    /** 一次判定结果：有无绑定 / 是否 all_users 放行 / 授权本次请求的角色 id 集合。 */
    public record Decision(boolean anyBinding, boolean allUsersBound, Set<Long> authorizingRoleIds) {
        static Decision noBinding() {
            return new Decision(false, false, Set.of());
        }
    }

    private record Snapshot(
            Map<String, Set<Long>> apiToPermissions,
            Map<Long, Set<Long>> roleToPermissions,
            Set<Long> allUsersPermissionIds,
            Set<Long> metaPermissionIds) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Set.of(), Set.of());
        }
    }

    private static Map<String, Set<Long>> immutable(Map<String, Set<Long>> source) {
        Map<String, Set<Long>> out = new HashMap<>();
        source.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    private static Map<Long, Set<Long>> immutableLong(Map<Long, Set<Long>> source) {
        Map<Long, Set<Long>> out = new HashMap<>();
        source.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }
}
