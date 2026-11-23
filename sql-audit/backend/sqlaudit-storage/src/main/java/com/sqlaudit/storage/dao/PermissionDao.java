package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.PermissionApi;

import java.util.List;

/**
 * 权限点目录与接口绑定的<strong>只读</strong>访问（对应 t_permission / t_permission_api）。
 *
 * <p>目录与绑定由 Flyway 的 {@code V2__seed.sql} 维护（界面只读、无写接口）：权限点要
 * 新增 / 改名 / 改绑定都走迁移文件，少一处手写副本就少一处漂移。「角色 → 权限点」的授权
 * 是可配置的，写接口在 {@link RoleDao}。</p>
 *
 * <p>鉴权判定只用两张表的全量快照（拦截器启动时加载、RBAC 写操作后重载）：
 * 「接口 → 权限点集合」来自 {@link #listAllApis()}，「角色 → 权限点集合」来自
 * {@link #listRolePermissionPairs()}，「所有人默认拥有」来自 {@link #listAll()} 的
 * {@code allUsers} 标志。</p>
 */
public interface PermissionDao {

    /** 权限点目录，按 (sort, id) 升序（界面分组展示顺序）。 */
    List<Permission> listAll();

    /** 按 code 查权限点；未找到返回 {@code null}。 */
    Permission findByCode(String code);

    /** 全部接口绑定，按 (permission_id, http_method, url_pattern) 升序。 */
    List<PermissionApi> listAllApis();

    /** 某权限点绑定的接口。 */
    List<PermissionApi> listApisByPermission(long permissionId);

    /**
     * 全部「角色 → 权限点」授权对，元素为 {@code [roleId, permissionId]}，
     * 按 (role_id, permission_id) 升序。供鉴权缓存一次性加载，避免 N 次查询。
     */
    List<long[]> listRolePermissionPairs();

    /** 持有某权限点的角色 id（权限目录页展示「哪些角色有这项」）。 */
    List<Long> listRoleIdsByPermission(long permissionId);
}
