package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PermissionDao} 的内存 fake 实现（供 auth 单测与本地联调解耦真实数据库）。
 *
 * <p>构造时按 {@link PermissionCode} 枚举预置目录（id 1..N 与枚举顺序一致）；
 * 角色 ↔ 权限点覆盖在内存中维护，{@code customized} 标志随 {@code replaceRolePermissions} 置位。</p>
 */
public class InMemoryPermissionDao implements PermissionDao {

    /** 与 {@link InMemoryRoleDao} 种子的角色 id 保持一致（DEV=1/LEAD=2/DBA=3/ADMIN=4）。 */
    private static final Map<Long, String> ROLE_CODE = Map.of(
            1L, "DEV", 2L, "LEAD", 3L, "DBA", 4L, "ADMIN");

    private final Map<Long, Permission> byId = new LinkedHashMap<>();
    private final Map<Long, List<Long>> permissionRoleIds = new ConcurrentHashMap<>();

    public InMemoryPermissionDao() {
        long id = 1;
        for (PermissionCode pc : PermissionCode.values()) {
            Permission p = new Permission();
            p.setId(id);
            p.setCode(pc.getCode());
            p.setName(pc.getDisplayName());
            p.setDescription(pc.getDescription());
            p.setCustomized(false);
            byId.put(id, p);
            id++;
        }
    }

    @Override
    public synchronized List<Permission> listAll() {
        List<Permission> out = new ArrayList<>(byId.size());
        for (Permission p : byId.values()) {
            out.add(copy(p));
        }
        return out;
    }

    @Override
    public synchronized Permission findByCode(String code) {
        for (Permission p : byId.values()) {
            if (p.getCode().equals(code)) {
                return copy(p);
            }
        }
        return null;
    }

    @Override
    public synchronized void insert(Permission permission) {
        if (permission.getId() == null) {
            permission.setId((long) byId.size() + 1);
        }
        byId.putIfAbsent(permission.getId(), copy(permission));
    }

    @Override
    public synchronized List<String> listRoleCodes(long permissionId) {
        List<Long> ids = permissionRoleIds.get(permissionId);
        if (ids == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.size());
        for (Long rid : ids) {
            String code = ROLE_CODE.get(rid);
            if (code != null) {
                out.add(code);
            }
        }
        return out;
    }

    @Override
    public synchronized void replaceRolePermissions(long permissionId, List<Long> roleIds, Long grantedBy) {
        Permission p = byId.get(permissionId);
        if (p != null) {
            p.setCustomized(true);
        }
        if (roleIds == null || roleIds.isEmpty()) {
            permissionRoleIds.remove(permissionId);
        } else {
            permissionRoleIds.put(permissionId, new ArrayList<>(roleIds));
        }
    }

    private static Permission copy(Permission src) {
        Permission dest = new Permission();
        dest.setId(src.getId());
        dest.setCode(src.getCode());
        dest.setName(src.getName());
        dest.setDescription(src.getDescription());
        dest.setCustomized(src.isCustomized());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
