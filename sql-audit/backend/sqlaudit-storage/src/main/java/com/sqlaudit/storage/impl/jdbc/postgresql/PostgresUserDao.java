package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * user 的 PostgreSQL 真实现（DDL §8 t_user 表；内存版语义基准 {@code InMemoryUserDao}，
 * SQL 逐句对照 MySQL 版 {@code MySqlUserDao}）。
 *
 * <p>自增 id 回填用 {@code INSERT ... RETURNING id}；时间戳 {@code TIMESTAMP(3)} 与
 * {@code LocalDateTime} 经 {@link Timestamp} 互转。</p>
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
        u.setSource(rs.getString("source"));
        u.setWeworkOpenid(rs.getString("wework_openid"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setTotpSecret(rs.getString("totp_secret"));
        u.setStatus(rs.getString("status"));
        Timestamp last = rs.getTimestamp("last_login_at");
        u.setLastLoginAt(last == null ? null : last.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        u.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        Timestamp deleted = rs.getTimestamp("deleted_at");
        u.setDeletedAt(deleted == null ? null : deleted.toLocalDateTime());
        return u;
    };

    @Override
    public long insert(User user) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_user (username, display_name, email, source, wework_openid, "
                            + "password_hash, totp_secret, status, last_login_at, created_at, updated_at, deleted_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getDisplayName());
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getSource());
            ps.setString(5, user.getWeworkOpenid());
            ps.setString(6, user.getPasswordHash());
            ps.setString(7, user.getTotpSecret());
            ps.setString(8, user.getStatus());
            ps.setTimestamp(9, user.getLastLoginAt() == null ? null : Timestamp.valueOf(user.getLastLoginAt()));
            ps.setTimestamp(10, Timestamp.valueOf(user.getCreatedAt() != null
                    ? user.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(11, Timestamp.valueOf(user.getUpdatedAt() != null
                    ? user.getUpdatedAt() : LocalDateTime.now()));
            ps.setTimestamp(12, user.getDeletedAt() == null ? null : Timestamp.valueOf(user.getDeletedAt()));
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
        return jdbc.query("SELECT * FROM t_user WHERE deleted_at IS NULL ORDER BY id", MAPPER);
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
    public int updateStatus(long id, String status) {
        return jdbc.update("UPDATE t_user SET status=?, updated_at=? WHERE id=?",
                status, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public int updateProfile(long id, String displayName, String email) {
        return jdbc.update("UPDATE t_user SET display_name=?, email=?, updated_at=? WHERE id=?",
                displayName, email, Timestamp.valueOf(LocalDateTime.now()), id);
    }
}
