package com.sqlaudit.storage.impl.jdbc.h2;

import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * instance 的 H2 真实现（DDL §4 t_instance 表；内存版语义基准
 * {@code InMemoryInstanceDao}，SQL 逐句对照 PostgreSQL 版 {@code PostgresInstanceDao}）。
 *
 * <p>H2（MODE=PostgreSQL）不支持 {@code INSERT ... RETURNING}，自增 id 回填改用
 * {@code GeneratedKeyHolder}（{@code new String[]{"id"}}）。删除为软删（置 deleted_at）。</p>
 */
public class H2InstanceDao implements InstanceDao {

    private final JdbcTemplate jdbc;

    public H2InstanceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Instance> MAPPER = (rs, i) -> {
        Instance u = new Instance();
        u.setId(rs.getLong("id"));
        u.setName(rs.getString("name"));
        u.setDialect(rs.getString("dialect"));
        u.setEnvironment(rs.getString("environment"));
        long ownerId = rs.getLong("owner_id");
        u.setOwnerId(rs.wasNull() ? null : ownerId);
        u.setHost(rs.getString("host"));
        int port = rs.getInt("port");
        u.setPort(rs.wasNull() ? null : port);
        u.setDefaultSchema(rs.getString("default_schema"));
        u.setReplicaDsn(rs.getString("replica_dsn"));
        long credentialId = rs.getLong("credential_id");
        u.setCredentialId(rs.wasNull() ? null : credentialId);
        u.setStatus(rs.getString("status"));
        long createdBy = rs.getLong("created_by");
        u.setCreatedBy(rs.wasNull() ? null : createdBy);
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        u.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        Timestamp deleted = rs.getTimestamp("deleted_at");
        u.setDeletedAt(deleted == null ? null : deleted.toLocalDateTime());
        return u;
    };

    @Override
    public List<Instance> listAll() {
        return jdbc.query("SELECT * FROM t_instance WHERE deleted_at IS NULL ORDER BY id", MAPPER);
    }

    @Override
    public Instance findById(long id) {
        List<Instance> result = jdbc.query(
                "SELECT * FROM t_instance WHERE id=? AND deleted_at IS NULL", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public long insert(Instance instance) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_instance (name, dialect, environment, owner_id, host, port, "
                            + "default_schema, replica_dsn, credential_id, status, created_by, "
                            + "created_at, updated_at, deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, instance.getName());
            ps.setString(2, instance.getDialect());
            ps.setString(3, instance.getEnvironment());
            ps.setLong(4, instance.getOwnerId());
            ps.setString(5, instance.getHost());
            ps.setInt(6, instance.getPort());
            ps.setString(7, instance.getDefaultSchema());
            ps.setString(8, instance.getReplicaDsn());
            setNullableLong(ps, 9, instance.getCredentialId());
            ps.setString(10, instance.getStatus() != null ? instance.getStatus() : "ACTIVE");
            ps.setLong(11, instance.getCreatedBy());
            ps.setTimestamp(12, Timestamp.valueOf(instance.getCreatedAt() != null
                    ? instance.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(13, Timestamp.valueOf(instance.getUpdatedAt() != null
                    ? instance.getUpdatedAt() : LocalDateTime.now()));
            ps.setTimestamp(14, instance.getDeletedAt() == null ? null : Timestamp.valueOf(instance.getDeletedAt()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        instance.setId(id);
        return id;
    }

    @Override
    public int update(Instance instance) {
        return jdbc.update(
                "UPDATE t_instance SET name=?, dialect=?, environment=?, owner_id=?, host=?, port=?, "
                        + "default_schema=?, replica_dsn=?, credential_id=?, status=?, updated_at=? "
                        + "WHERE id=? AND deleted_at IS NULL",
                instance.getName(),
                instance.getDialect(),
                instance.getEnvironment(),
                instance.getOwnerId(),
                instance.getHost(),
                instance.getPort(),
                instance.getDefaultSchema(),
                instance.getReplicaDsn(),
                instance.getCredentialId(),
                instance.getStatus(),
                Timestamp.valueOf(LocalDateTime.now()),
                instance.getId());
    }

    @Override
    public int delete(long id) {
        return jdbc.update(
                "UPDATE t_instance SET deleted_at=? WHERE id=? AND deleted_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
