package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.model.Credential;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * credential 的 MySQL 真实现（DDL §5 t_credential 表；内存版语义基准 {@code InMemoryCredentialDao}）。
 *
 * <p>{@code password_enc} 为 VARBINARY：入库把密文文本按 UTF-8 转字节（{@code setBytes}），
 * 出库 {@code getBytes} 后还原为 base64 文本，解密在消费方完成。</p>
 */
public class MySqlCredentialDao implements CredentialDao {

    private final JdbcTemplate jdbc;

    public MySqlCredentialDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Credential> MAPPER = (rs, i) -> {
        Credential c = new Credential();
        c.setId(rs.getLong("id"));
        long instanceId = rs.getLong("instance_id");
        c.setInstanceId(rs.wasNull() ? null : instanceId);
        c.setAccountType(AccountType.fromCode(rs.getString("account_type")));
        c.setUsername(rs.getString("username"));
        byte[] enc = rs.getBytes("password_enc");
        c.setPasswordEnc(enc == null ? null : new String(enc, StandardCharsets.UTF_8));
        int keyVersion = rs.getInt("key_version");
        c.setKeyVersion(rs.wasNull() ? null : keyVersion);
        Timestamp rotated = rs.getTimestamp("last_rotated_at");
        c.setLastRotatedAt(rotated == null ? null : rotated.toLocalDateTime());
        Timestamp expires = rs.getTimestamp("expires_at");
        c.setExpiresAt(expires == null ? null : expires.toLocalDateTime());
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
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_credential (instance_id, account_type, username, password_enc, "
                            + "key_version, last_rotated_at, expires_at, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, credential.getInstanceId());
            ps.setString(2, CodedEnum.code(credential.getAccountType()));
            ps.setString(3, credential.getUsername());
            ps.setBytes(4, credential.getPasswordEnc().getBytes(StandardCharsets.UTF_8));
            ps.setInt(5, credential.getKeyVersion() != null ? credential.getKeyVersion() : 1);
            ps.setTimestamp(6, credential.getLastRotatedAt() == null
                    ? null : Timestamp.valueOf(credential.getLastRotatedAt()));
            ps.setTimestamp(7, credential.getExpiresAt() == null
                    ? null : Timestamp.valueOf(credential.getExpiresAt()));
            ps.setTimestamp(8, Timestamp.valueOf(credential.getCreatedAt() != null
                    ? credential.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(9, Timestamp.valueOf(credential.getUpdatedAt() != null
                    ? credential.getUpdatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        credential.setId(id);
        return id;
    }

    @Override
    public int update(Credential credential) {
        return jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_credential SET username=?, password_enc=?, key_version=?, "
                            + "last_rotated_at=?, updated_at=? WHERE id=?");
            ps.setString(1, credential.getUsername());
            ps.setBytes(2, credential.getPasswordEnc().getBytes(StandardCharsets.UTF_8));
            ps.setInt(3, credential.getKeyVersion() != null ? credential.getKeyVersion() : 1);
            ps.setTimestamp(4, credential.getLastRotatedAt() == null
                    ? null : Timestamp.valueOf(credential.getLastRotatedAt()));
            ps.setTimestamp(5, Timestamp.valueOf(credential.getUpdatedAt() != null
                    ? credential.getUpdatedAt() : LocalDateTime.now()));
            ps.setLong(6, credential.getId());
            return ps;
        });
    }

    @Override
    public List<Credential> listByInstance(long instanceId) {
        return jdbc.query("SELECT * FROM t_credential WHERE instance_id=? ORDER BY id",
                MAPPER, instanceId);
    }
}
