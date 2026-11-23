package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.UserSource;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.User;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * user 的 PostgreSQL 真实现（t_user 表，SQL 逐句对照 MySQL 版 {@code MySqlUserDao}）。
 *
 * <p>自增 id 回填用 {@code INSERT ... RETURNING id}；时间戳 {@code TIMESTAMP(3)} 与
 * {@code LocalDateTime} 经 {@link Timestamp} 互转。事务边界由调用方保证，本类不开事务
 * （两个防自锁方法必须在调用方事务内才有意义）。</p>
 */
public class PostgresUserDao implements UserDao {

    private final JdbcTemplate jdbc;

    public PostgresUserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<User> MAPPER = (rs, i) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setDisplayName(rs.getString("display_name"));
        u.setEmail(rs.getString("email"));
        u.setSource(UserSource.fromCode(rs.getString("source")));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setStatus(UserStatus.fromCode(rs.getString("status")));
        Timestamp last = rs.getTimestamp("last_login_at");
        u.setLastLoginAt(last == null ? null : last.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        u.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return u;
    };

    @Override
    public long insert(User user) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_user (username, display_name, email, source, password_hash, "
                            + "status, last_login_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getDisplayName());
            ps.setString(3, user.getEmail());
            ps.setString(4, CodedEnum.code(user.getSource() != null ? user.getSource() : UserSource.LOCAL));
            ps.setString(5, user.getPasswordHash());
            ps.setString(6, CodedEnum.code(user.getStatus() != null ? user.getStatus() : UserStatus.ACTIVE));
            ps.setTimestamp(7, user.getLastLoginAt() == null ? null : Timestamp.valueOf(user.getLastLoginAt()));
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(8, Timestamp.valueOf(user.getCreatedAt() != null
                    ? user.getCreatedAt() : now));
            ps.setTimestamp(9, Timestamp.valueOf(user.getUpdatedAt() != null
                    ? user.getUpdatedAt() : now));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        user.setId(id);
        return id;
    }

    @Override
    public User findByUsername(String username) {
        List<User> result = jdbc.query("SELECT * FROM t_user WHERE username=?", MAPPER, username);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public User findById(long id) {
        List<User> result = jdbc.query("SELECT * FROM t_user WHERE id=?", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<User> listAll() {
        return jdbc.query("SELECT * FROM t_user ORDER BY id", MAPPER);
    }

    @Override
    public List<User> listActive() {
        return jdbc.query("SELECT * FROM t_user WHERE status=? ORDER BY id",
                MAPPER, UserStatus.ACTIVE.getCode());
    }

    @Override
    public int updateLastLoginAt(long id, LocalDateTime at) {
        return jdbc.update("UPDATE t_user SET last_login_at=? WHERE id=?", Timestamp.valueOf(at), id);
    }

    @Override
    public int updatePassword(long id, String passwordHash) {
        return jdbc.update("UPDATE t_user SET password_hash=?, updated_at=? WHERE id=?",
                passwordHash, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public int updateStatus(long id, UserStatus status) {
        return jdbc.update("UPDATE t_user SET status=?, updated_at=? WHERE id=?",
                CodedEnum.code(status), Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public int updateProfile(long id, String displayName, String email) {
        return jdbc.update("UPDATE t_user SET display_name=?, email=?, updated_at=? WHERE id=?",
                displayName, email, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public int countActiveMetaPermissionHolders() {
        // 「经任意角色持有任一元权限点的 ACTIVE 用户」计数：必须在 RBAC 写事务内、写之后调用
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM t_user u "
                        + "JOIN t_user_role ur ON ur.user_id = u.id "
                        + "JOIN t_role_permission rp ON rp.role_id = ur.role_id "
                        + "JOIN t_permission p ON p.id = rp.permission_id "
                        + "WHERE u.status = ? AND p.meta = TRUE",
                Integer.class, UserStatus.ACTIVE.getCode());
        return count == null ? 0 : count;
    }

    @Override
    public List<Long> lockMetaPermissions() {
        // 行锁串行化全部并发 RBAC 写操作；须在调用方事务内，事务提交自动释放
        return jdbc.queryForList(
                "SELECT id FROM t_permission WHERE meta = TRUE ORDER BY id FOR UPDATE", Long.class);
    }
}
