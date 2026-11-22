package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * role 的 MySQL 真实现（DDL §8 t_role / t_user_role 表；内存版语义基准 {@code InMemoryRoleDao}）。
 *
 * <p>{@code listByUserId} 经 t_user_role 多对多关联取角色；{@code listAll} 按 id 升序。</p>
 */
public class MySqlRoleDao implements RoleDao {

    private final JdbcTemplate jdbc;

    public MySqlRoleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Role> MAPPER = (rs, i) -> {
        Role r = new Role();
        r.setId(rs.getLong("id"));
        r.setCode(rs.getString("code"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return r;
    };

    @Override
    public List<Role> listByUserId(long userId) {
        return jdbc.query(
                "SELECT r.* FROM t_role r JOIN t_user_role ur ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.id",
                MAPPER, userId);
    }

    @Override
    public List<Role> listAll() {
        return jdbc.query("SELECT * FROM t_role ORDER BY id", MAPPER);
    }

    @Override
    public int removeAllRoles(long userId) {
        return jdbc.update("DELETE FROM t_user_role WHERE user_id = ?", userId);
    }

    @Override
    public void assignRoles(long userId, List<Long> roleIds, Long grantedBy) {
        for (Long roleId : roleIds) {
            jdbc.update("INSERT INTO t_user_role (user_id, role_id, granted_by) VALUES (?,?,?)",
                    userId, roleId, grantedBy);
        }
    }
}
