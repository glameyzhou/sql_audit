package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Permission;

import java.util.List;

/**
 * 权限点存储（对应 t_permission / t_role_permission 表，见 sql-audit-rbac-权限可配置化设计.md）。
 *
 * <p>目录由 {@code PermissionCode} 枚举在启动时同步（insert-if-absent）；角色 ↔ 权限点的
 * 覆盖配置只在 {@code customized=true} 后生效，未配置时走代码兜底（{@code PermissionCode#getDefaultRoles()}）。</p>
 */
public interface PermissionDao {

    /** 权限点目录（含 customized 标志，按 id 升序）。 */
    List<Permission> listAll();

    /** 按 code 查权限点；未找到返回 {@code null}。 */
    Permission findByCode(String code);

    /** 插入权限点（枚举目录 seed 用）。 */
    void insert(Permission permission);

    /** 某权限点已授权的角色 code 列表（customized=true 时生效；未配置返回空）。 */
    List<String> listRoleCodes(long permissionId);

    /** 重设某权限点的授权角色（删旧 + 插新，并置 customized=true；roleIds 可为空=关闭该权限点）。 */
    void replaceRolePermissions(long permissionId, List<Long> roleIds, Long grantedBy);
}
