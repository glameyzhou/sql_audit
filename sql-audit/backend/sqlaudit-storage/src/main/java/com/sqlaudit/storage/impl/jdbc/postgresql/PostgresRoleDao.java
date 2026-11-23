package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.InstanceScope;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.impl.jdbc.JdbcTx;
import com.sqlaudit.storage.model.Role;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * role 的 PostgreSQL 真实现（t_role / t_user_role / t_role_permission / t_role_instance 四表，
 * SQL 逐句对照 MySQL 版 {@code MySqlRoleDao}）。
 *
 * <p>三个「整体替换」方法与 {@link #delete} 都在 {@link JdbcTx} 事务内执行
 * （PROPAGATION_REQUIRED，有外层事务就加入）：删旧 + 插新中途失败会留下零权限角色，
 * 这是直接影响线上可用性的状态，原子性不能指望调用方。
 * 自增 id 回填用 {@code INSERT ... RETURNING id}。</p>
 */
public class PostgresRoleDao implements RoleDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresRoleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = JdbcTx.of(jdbc);
    }

    private static final RowMapper<Role> MAPPER = (rs, i) -> {
        Role r = new Role();
        r.setId(rs.getLong("id"));
        r.setCode(rs.getString("code"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        r.setBuiltin(rs.getBoolean("builtin"));
        r.setInstanceScope(InstanceScope.fromCode(rs.getString("instance_scope")));
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        r.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return r;
    };

    // ---- 角色本身 ----

    @Override
    public List<Role> listAll() {
        return jdbc.query("SELECT * FROM t_role ORDER BY id", MAPPER);
    }

    @Override
    public Role findById(long id) {
        List<Role> list = jdbc.query("SELECT * FROM t_role WHERE id=?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public Role findByCode(String code) {
        List<Role> list = jdbc.query("SELECT * FROM t_role WHERE code=?", MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public long insert(Role role) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_role (code, name, description, builtin, instance_scope, "
                            + "created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, role.getCode());
            ps.setString(2, role.getName());
            ps.setString(3, role.getDescription());
            ps.setBoolean(4, role.isBuiltin());
            ps.setString(5, CodedEnum.code(role.getInstanceScope() != null
                    ? role.getInstanceScope() : InstanceScope.ALL));
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(6, Timestamp.valueOf(role.getCreatedAt() != null ? role.getCreatedAt() : now));
            ps.setTimestamp(7, Timestamp.valueOf(role.getUpdatedAt() != null ? role.getUpdatedAt() : now));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        role.setId(id);
        return id;
    }

    @Override
    public int update(Role role) {
        // 刻意不更新 code / builtin：内置角色的 code 不可改，自定义角色改 code 等于换一个角色
        return jdbc.update(
                "UPDATE t_role SET name=?, description=?, instance_scope=?, updated_at=? WHERE id=?",
                role.getName(), role.getDescription(),
                CodedEnum.code(role.getInstanceScope() != null ? role.getInstanceScope() : InstanceScope.ALL),
                Timestamp.valueOf(LocalDateTime.now()), role.getId());
    }

    @Override
    public int delete(long id) {
        Integer affected = tx.execute(status -> {
            jdbc.update("DELETE FROM t_user_role WHERE role_id=?", id);
            jdbc.update("DELETE FROM t_role_permission WHERE role_id=?", id);
            jdbc.update("DELETE FROM t_role_instance WHERE role_id=?", id);
            return jdbc.update("DELETE FROM t_role WHERE id=?", id);
        });
        return affected == null ? 0 : affected;
    }

    // ---- 用户 ↔ 角色 ----

    @Override
    public List<Role> listByUserId(long userId) {
        return jdbc.query(
                "SELECT r.* FROM t_role r JOIN t_user_role ur ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? ORDER BY r.id",
                MAPPER, userId);
    }

    @Override
    public int countUsers(long roleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user_role WHERE role_id=?", Integer.class, roleId);
        return count == null ? 0 : count;
    }

    @Override
    public boolean hasActiveMember(long roleId, Long excludeUserId) {
        String sql = "SELECT COUNT(*) FROM t_user_role ur JOIN t_user u ON u.id = ur.user_id "
                + "WHERE ur.role_id = ? AND u.status = ?"
                + (excludeUserId == null ? "" : " AND u.id <> ?");
        Integer count = excludeUserId == null
                ? jdbc.queryForObject(sql, Integer.class, roleId, UserStatus.ACTIVE.getCode())
                : jdbc.queryForObject(sql, Integer.class, roleId, UserStatus.ACTIVE.getCode(), excludeUserId);
        return count != null && count > 0;
    }

    @Override
    public void replaceUserRoles(long userId, List<Long> roleIds, Long grantedBy) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM t_user_role WHERE user_id=?", userId);
            for (Long roleId : roleIds) {
                jdbc.update("INSERT INTO t_user_role (user_id, role_id, granted_by) VALUES (?,?,?)",
                        userId, roleId, grantedBy);
            }
        });
    }

    // ---- 角色 ↔ 权限点 ----

    @Override
    public List<Long> listPermissionIds(long roleId) {
        return jdbc.queryForList(
                "SELECT permission_id FROM t_role_permission WHERE role_id=? ORDER BY permission_id",
                Long.class, roleId);
    }

    @Override
    public void replacePermissions(long roleId, List<Long> permissionIds, Long grantedBy) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM t_role_permission WHERE role_id=?", roleId);
            for (Long permissionId : permissionIds) {
                jdbc.update("INSERT INTO t_role_permission (role_id, permission_id, granted_by) VALUES (?,?,?)",
                        roleId, permissionId, grantedBy);
            }
        });
    }

    // ---- 角色 ↔ 实例 ----

    @Override
    public List<Long> listInstanceIds(long roleId) {
        return jdbc.queryForList(
                "SELECT instance_id FROM t_role_instance WHERE role_id=? ORDER BY instance_id",
                Long.class, roleId);
    }

    @Override
    public void replaceInstances(long roleId, List<Long> instanceIds, Long grantedBy) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM t_role_instance WHERE role_id=?", roleId);
            for (Long instanceId : instanceIds) {
                jdbc.update("INSERT INTO t_role_instance (role_id, instance_id, granted_by) VALUES (?,?,?)",
                        roleId, instanceId, grantedBy);
            }
        });
    }
}
