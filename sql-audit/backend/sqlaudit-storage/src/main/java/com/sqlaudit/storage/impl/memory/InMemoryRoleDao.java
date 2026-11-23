package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link RoleDao} 的内存 fake 实现（供 auth 单测与本地联调解耦真实数据库）。
 *
 * <p>预置 V1 四条内置角色（DEV/LEAD/DBA/ADMIN，id 与 DDL 种子顺序一致，rank 与
 * RoleCode 权重一致）。支持自定义角色增删改；用户-角色多对多在内存维护。</p>
 */
public class InMemoryRoleDao implements RoleDao {

    private final Map<Long, Role> rolesById = new LinkedHashMap<>();
    private final Map<Long, List<Role>> userRoles = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(4);

    public InMemoryRoleDao() {
        seedRole(1L, RoleCode.DEV.getCode(), "开发", "提交工单、查询通道", 0);
        seedRole(2L, RoleCode.LEAD.getCode(), "主管", "审批（非本人）", 1);
        seedRole(3L, RoleCode.DBA.getCode(), "DBA", "执行/二次确认、规则与流程配置", 2);
        seedRole(4L, RoleCode.ADMIN.getCode(), "管理员", "凭据管理、审计检索导出、全量配置", 3);
    }

    @Override
    public synchronized List<Role> listByUserId(long userId) {
        List<Role> roles = userRoles.get(userId);
        return roles == null ? List.of() : List.copyOf(roles);
    }

    @Override
    public synchronized List<Role> listAll() {
        List<Role> out = new ArrayList<>(rolesById.size());
        for (Role role : rolesById.values()) {
            out.add(copy(role));
        }
        return out;
    }

    @Override
    public synchronized int removeAllRoles(long userId) {
        List<Role> removed = userRoles.remove(userId);
        return removed == null ? 0 : removed.size();
    }

    @Override
    public synchronized void assignRoles(long userId, List<Long> roleIds, Long grantedBy) {
        List<Role> roles = new ArrayList<>();
        for (Long roleId : roleIds) {
            Role role = rolesById.get(roleId);
            if (role != null) {
                roles.add(copy(role));
            }
        }
        if (roles.isEmpty()) {
            userRoles.remove(userId);
        } else {
            userRoles.put(userId, roles);
        }
    }

    @Override
    public synchronized Role findByCode(String code) {
        for (Role role : rolesById.values()) {
            if (role.getCode().equals(code)) {
                return copy(role);
            }
        }
        return null;
    }

    @Override
    public synchronized long insert(Role role) {
        long id = idGenerator.incrementAndGet();
        role.setId(id);
        role.setCreatedAt(LocalDateTime.now());
        rolesById.put(id, copy(role));
        return id;
    }

    @Override
    public synchronized int update(Role role) {
        Role existing = rolesById.get(role.getId());
        if (existing == null) {
            return 0;
        }
        if (role.getName() != null) {
            existing.setName(role.getName());
        }
        if (role.getDescription() != null) {
            existing.setDescription(role.getDescription());
        }
        if (role.getRank() != null) {
            existing.setRank(role.getRank());
        }
        return 1;
    }

    @Override
    public synchronized int delete(long id) {
        if (rolesById.remove(id) == null) {
            return 0;
        }
        // 清理用户-角色关联（用户角色列表里移除该角色）
        for (List<Role> roles : userRoles.values()) {
            roles.removeIf(r -> r.getId() != null && r.getId() == id);
        }
        return 1;
    }

    /**
     * 给用户授予角色（种子账号用；对应 t_user_role 的写入，接口未暴露故放具体实现）。
     */
    public synchronized void grantByCode(long userId, String roleCode) {
        Role role = findByCode(roleCode);
        if (role == null) {
            throw new IllegalArgumentException("未知角色: " + roleCode);
        }
        userRoles.computeIfAbsent(userId, k -> new ArrayList<>()).add(copy(role));
    }

    private void seedRole(long id, String code, String name, String description, int rank) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setRank(rank);
        role.setCreatedAt(LocalDateTime.now());
        rolesById.put(id, role);
    }

    private static Role copy(Role src) {
        Role dest = new Role();
        dest.setId(src.getId());
        dest.setCode(src.getCode());
        dest.setName(src.getName());
        dest.setDescription(src.getDescription());
        dest.setRank(src.getRank());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
