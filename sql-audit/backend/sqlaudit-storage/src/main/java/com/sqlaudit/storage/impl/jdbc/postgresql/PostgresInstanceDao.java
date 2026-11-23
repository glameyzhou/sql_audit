package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * instance 的 PostgreSQL 真实现（t_instance 表，SQL 逐句对照 MySQL 版
 * {@code MySqlInstanceDao}）。
 *
 * <p>删除为软删（置 deleted_at）；{@code listAll()} / {@code findById()} 只返回未删除记录，
 * {@link #findByName} 也只匹配未删除行——软删实例的名字可以复用，「未删除实例中 name 唯一」
 * 由服务层据此校验（实例名无库级唯一约束）。PG 无 {@code ON UPDATE}：
 * {@code updated_at} 由 DAO 显式写。自增 id 回填用 {@code INSERT ... RETURNING id}。</p>
 */
public class PostgresInstanceDao implements InstanceDao {

    private final JdbcTemplate jdbc;

    public PostgresInstanceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Instance> MAPPER = (rs, i) -> {
        Instance u = new Instance();
        u.setId(rs.getLong("id"));
        u.setName(rs.getString("name"));
        u.setDialect(Dialect.fromCode(rs.getString("dialect")));
        u.setOwnerId(rs.getLong("owner_id"));
        u.setHost(rs.getString("host"));
        u.setPort(rs.getObject("port", Integer.class));
        u.setDefaultSchema(rs.getString("default_schema"));
        u.setStatus(InstanceStatus.fromCode(rs.getString("status")));
        u.setCreatedBy(rs.getLong("created_by"));
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
    public Instance findByName(String name) {
        List<Instance> result = jdbc.query(
                "SELECT * FROM t_instance WHERE name=? AND deleted_at IS NULL LIMIT 1", MAPPER, name);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public long insert(Instance instance) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_instance (name, dialect, owner_id, host, port, default_schema, "
                            + "status, created_by, created_at, updated_at, deleted_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, instance.getName());
            ps.setString(2, CodedEnum.code(instance.getDialect()));
            ps.setLong(3, instance.getOwnerId());
            ps.setString(4, instance.getHost());
            ps.setInt(5, instance.getPort());
            ps.setString(6, instance.getDefaultSchema());
            ps.setString(7, CodedEnum.code(instance.getStatus() != null
                    ? instance.getStatus() : InstanceStatus.ACTIVE));
            ps.setLong(8, instance.getCreatedBy());
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(9, Timestamp.valueOf(instance.getCreatedAt() != null
                    ? instance.getCreatedAt() : now));
            ps.setTimestamp(10, Timestamp.valueOf(instance.getUpdatedAt() != null
                    ? instance.getUpdatedAt() : now));
            ps.setTimestamp(11, instance.getDeletedAt() == null
                    ? null : Timestamp.valueOf(instance.getDeletedAt()));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        instance.setId(id);
        return id;
    }

    @Override
    public int update(Instance instance) {
        return jdbc.update(
                "UPDATE t_instance SET name=?, dialect=?, owner_id=?, host=?, port=?, default_schema=?, "
                        + "status=?, updated_at=? WHERE id=? AND deleted_at IS NULL",
                instance.getName(),
                CodedEnum.code(instance.getDialect()),
                instance.getOwnerId(),
                instance.getHost(),
                instance.getPort(),
                instance.getDefaultSchema(),
                CodedEnum.code(instance.getStatus()),
                Timestamp.valueOf(LocalDateTime.now()),
                instance.getId());
    }

    @Override
    public int delete(long id) {
        return jdbc.update(
                "UPDATE t_instance SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), id);
    }
}
