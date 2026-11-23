package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.PermissionApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * permission 的 MySQL 真实现（t_permission / t_permission_api / t_role_permission 只读）。
 *
 * <p>目录与接口绑定由 Flyway 种子维护，本类<strong>没有写方法</strong>；
 * 「角色 → 权限点」的授权写在 {@code MySqlRoleDao}。</p>
 */
public class MySqlPermissionDao implements PermissionDao {

    private final JdbcTemplate jdbc;

    public MySqlPermissionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Permission> MAPPER = (rs, i) -> {
        Permission p = new Permission();
        p.setId(rs.getLong("id"));
        p.setCode(rs.getString("code"));
        p.setName(rs.getString("name"));
        p.setModule(rs.getString("module"));
        p.setDescription(rs.getString("description"));
        p.setSort(rs.getInt("sort"));
        p.setAllUsers(rs.getBoolean("all_users"));
        p.setMeta(rs.getBoolean("meta"));
        Timestamp created = rs.getTimestamp("created_at");
        p.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return p;
    };

    private static final RowMapper<PermissionApi> API_MAPPER = (rs, i) -> {
        PermissionApi api = new PermissionApi();
        api.setId(rs.getLong("id"));
        api.setPermissionId(rs.getLong("permission_id"));
        api.setHttpMethod(rs.getString("http_method"));
        api.setUrlPattern(rs.getString("url_pattern"));
        return api;
    };

    @Override
    public List<Permission> listAll() {
        return jdbc.query("SELECT * FROM t_permission ORDER BY sort, id", MAPPER);
    }

    @Override
    public Permission findByCode(String code) {
        List<Permission> list = jdbc.query("SELECT * FROM t_permission WHERE code=?", MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<PermissionApi> listAllApis() {
        return jdbc.query(
                "SELECT * FROM t_permission_api ORDER BY permission_id, http_method, url_pattern",
                API_MAPPER);
    }

    @Override
    public List<PermissionApi> listApisByPermission(long permissionId) {
        return jdbc.query(
                "SELECT * FROM t_permission_api WHERE permission_id=? ORDER BY http_method, url_pattern",
                API_MAPPER, permissionId);
    }

    @Override
    public List<long[]> listRolePermissionPairs() {
        return jdbc.query(
                "SELECT role_id, permission_id FROM t_role_permission ORDER BY role_id, permission_id",
                (rs, i) -> new long[]{rs.getLong("role_id"), rs.getLong("permission_id")});
    }

    @Override
    public List<Long> listRoleIdsByPermission(long permissionId) {
        return jdbc.queryForList(
                "SELECT role_id FROM t_role_permission WHERE permission_id=? ORDER BY role_id",
                Long.class, permissionId);
    }
}
