package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限点判定服务：<strong>界面配置优先、代码兜底</strong>（见 sql-audit-rbac-权限可配置化设计.md）。
 *
 * <p>每个权限点的生效角色：{@code t_permission.customized=false} → 用
 * {@link PermissionCode#getDefaultRoles()}（代码兜底，= 原 {@code @RequireRole} 默认）；
 * {@code customized=true} → 用 {@code t_role_permission}（界面覆盖，可为空 = 关闭该权限点）。
 * 元权限 {@link PermissionCode#PERMISSION_ADMIN} 永不进 DB，硬编码 ADMIN。</p>
 *
 * <p>生效角色结果按 code 缓存，配置保存后由 {@link #invalidate} 写即失效（降权即时生效）。</p>
 */
@Service
public class PermissionService {

    private final PermissionDao permissionDao;
    private final Map<String, List<String>> allowedCache = new ConcurrentHashMap<>();

    public PermissionService(PermissionDao permissionDao) {
        this.permissionDao = permissionDao;
    }

    /** 判断用户角色是否拥有某权限点。 */
    public boolean hasPermission(List<String> userRoles, PermissionCode permission) {
        List<String> allowed = allowedRoles(permission);
        for (String role : userRoles) {
            if (allowed.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** 某权限点的生效角色 code（界面配置优先，未配置用代码兜底）。 */
    public List<String> allowedRoles(PermissionCode permission) {
        if (!permission.isConfigurable()) {
            return List.of(RoleCode.ADMIN.getCode());
        }
        List<String> cached = allowedCache.get(permission.getCode());
        if (cached != null) {
            return cached;
        }
        Permission p = permissionDao.findByCode(permission.getCode());
        List<String> allowed;
        if (p != null && p.isCustomized()) {
            allowed = List.copyOf(permissionDao.listRoleCodes(p.getId()));
        } else {
            allowed = permission.getDefaultRoles().stream().map(RoleCode::getCode).toList();
        }
        allowedCache.put(permission.getCode(), allowed);
        return allowed;
    }

    /** 配置保存后失效缓存（按 code）。 */
    public void invalidate(String code) {
        allowedCache.remove(code);
    }
}
