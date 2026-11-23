package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.model.Credential;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * credential 的 PostgreSQL 真实现（t_credential 表，SQL 逐句对照 MySQL 版
 * {@code MySqlCredentialDao}）。
 *
 * <p>{@code password_enc} 为 {@code VARCHAR(512)}——存的本来就是
 * {@code CredentialCrypto} 产出的密文文本（{@code ivBase64.ciphertextBase64}），
 * 直接 {@code setString} / {@code getString}；解密在消费方（worker / query 建连接）
 * 完成，DAO 层不触碰明文。自增 id 回填用 {@code INSERT ... RETURNING id}。</p>
 */
public class PostgresCredentialDao implements CredentialDao {

    private final JdbcTemplate jdbc;

    public PostgresCredentialDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Credential> MAPPER = (rs, i) -> {
        Credential c = new Credential();
        c.setId(rs.getLong("id"));
        c.setInstanceId(rs.getLong("instance_id"));
        c.setAccountType(AccountType.fromCode(rs.getString("account_type")));
        c.setUsername(rs.getString("username"));
        c.setPasswordEnc(rs.getString("password_enc"));
        Timestamp rotated = rs.getTimestamp("last_rotated_at");
        c.setLastRotatedAt(rotated == null ? null : rotated.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        c.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        c.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return c;
    };

    @Override
    public Credential findByInstanceAndType(long instanceId, AccountType accountType) {
        List<Credential> result = jdbc.query(
                "SELECT * FROM t_credential WHERE instance_id=? AND account_type=? LIMIT 1",
                MAPPER, instanceId, CodedEnum.code(accountType));
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public long insert(Credential credential) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_credential (instance_id, account_type, username, password_enc, "
                            + "last_rotated_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id");
            ps.setLong(1, credential.getInstanceId());
            ps.setString(2, CodedEnum.code(credential.getAccountType()));
            ps.setString(3, credential.getUsername());
            ps.setString(4, credential.getPasswordEnc());
            ps.setTimestamp(5, credential.getLastRotatedAt() == null
                    ? null : Timestamp.valueOf(credential.getLastRotatedAt()));
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(6, Timestamp.valueOf(credential.getCreatedAt() != null
                    ? credential.getCreatedAt() : now));
            ps.setTimestamp(7, Timestamp.valueOf(credential.getUpdatedAt() != null
                    ? credential.getUpdatedAt() : now));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        credential.setId(id);
        return id;
    }

    @Override
    public int update(Credential credential) {
        // PG 无 ON UPDATE：updated_at 显式写
        return jdbc.update(
                "UPDATE t_credential SET username=?, password_enc=?, last_rotated_at=?, updated_at=? WHERE id=?",
                credential.getUsername(),
                credential.getPasswordEnc(),
                credential.getLastRotatedAt() == null ? null : Timestamp.valueOf(credential.getLastRotatedAt()),
                Timestamp.valueOf(LocalDateTime.now()),
                credential.getId());
    }

    @Override
    public List<Credential> listByInstance(long instanceId) {
        return jdbc.query("SELECT * FROM t_credential WHERE instance_id=? ORDER BY id",
                MAPPER, instanceId);
    }
}
