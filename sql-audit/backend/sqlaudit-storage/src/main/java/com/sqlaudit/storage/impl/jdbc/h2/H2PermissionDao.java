package com.sqlaudit.storage.impl.jdbc.h2;

import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * permission 的 H2 真实现（t_permission / t_role_permission 表；语义基准
 * {@code com.sqlaudit.storage.impl.memory.InMemoryPermissionDao}，SQL 逐句对照
 * PostgreSQL 版 {@code PostgresPermissionDao}）。
 */
public class H2PermissionDao implements PermissionDao {

    private final JdbcTemplate jdbc;

    public H2PermissionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Permission> MAPPER = (rs, i) -> {
        Permission p = new Permission();
        p.setId(rs.getLong("id"));
        p.setCode(rs.getString("code"));
        p.setName(rs.getString("name"));
        p.setDescription(rs.getString("description"));
        p.setCustomized(rs.getBoolean("customized"));
        Timestamp created = rs.getTimestamp("created_at");
        p.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return p;
    };

    @Override
    public List<Permission> listAll() {
        return jdbc.query("SELECT * FROM t_permission ORDER BY id", MAPPER);
    }

    @Override
    public Permission findByCode(String code) {
        List<Permission> list = jdbc.query("SELECT * FROM t_permission WHERE code = ?", MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void insert(Permission permission) {
        jdbc.update("INSERT INTO t_permission (code, name, description, customized) VALUES (?,?,?,?)",
                permission.getCode(), permission.getName(), permission.getDescription(),
                permission.isCustomized());
    }

    @Override
    public List<String> listRoleCodes(long permissionId) {
        return jdbc.queryForList(
                "SELECT r.code FROM t_role r JOIN t_role_permission rp ON r.id = rp.role_id "
                        + "WHERE rp.permission_id = ? ORDER BY r.id",
                String.class, permissionId);
    }

    @Override
    public void replaceRolePermissions(long permissionId, List<Long> roleIds, Long grantedBy) {
        jdbc.update("DELETE FROM t_role_permission WHERE permission_id = ?", permissionId);
        for (Long roleId : roleIds) {
            jdbc.update("INSERT INTO t_role_permission (permission_id, role_id, granted_by) VALUES (?,?,?)",
                    permissionId, roleId, grantedBy);
        }
        jdbc.update("UPDATE t_permission SET customized = ? WHERE id = ?", true, permissionId);
    }
}
