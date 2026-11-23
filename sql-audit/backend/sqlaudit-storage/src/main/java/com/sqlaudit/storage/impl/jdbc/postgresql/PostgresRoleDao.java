package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * role 的 PostgreSQL 真实现（DDL §8 t_role / t_user_role 表；内存版语义基准
 * {@code InMemoryRoleDao}，SQL 逐句对照 MySQL 版 {@code MySqlRoleDao}）。
 */
public class PostgresRoleDao implements RoleDao {

    private final JdbcTemplate jdbc;

    public PostgresRoleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Role> MAPPER = (rs, i) -> {
        Role r = new Role();
        r.setId(rs.getLong("id"));
        r.setCode(rs.getString("code"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        r.setRank(rs.getInt("rank"));
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

    @Override
    public Role findByCode(String code) {
        List<Role> list = jdbc.query("SELECT * FROM t_role WHERE code = ?", MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public long insert(Role role) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_role (code, name, description, rank) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, role.getCode());
            ps.setString(2, role.getName());
            ps.setString(3, role.getDescription());
            ps.setInt(4, role.getRank() == null ? 0 : role.getRank());
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        role.setId(id);
        return id;
    }

    @Override
    public int update(Role role) {
        return jdbc.update("UPDATE t_role SET name=?, description=?, rank=? WHERE id=?",
                role.getName(), role.getDescription(), role.getRank() == null ? 0 : role.getRank(), role.getId());
    }

    @Override
    public int delete(long id) {
        jdbc.update("DELETE FROM t_user_role WHERE role_id = ?", id);
        jdbc.update("DELETE FROM t_role_permission WHERE role_id = ?", id);
        return jdbc.update("DELETE FROM t_role WHERE id=?", id);
    }
}
