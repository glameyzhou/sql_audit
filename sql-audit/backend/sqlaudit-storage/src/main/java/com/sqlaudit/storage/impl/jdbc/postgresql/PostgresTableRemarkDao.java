package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.storage.dao.TableRemarkDao;
import com.sqlaudit.storage.model.TableRemark;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 表备注词典的 PostgreSQL 真实现（t_table_remark 表，SQL 逐句对照 MySQL 版
 * {@code MySqlTableRemarkDao}）。
 *
 * <p>{@link #upsert} 用 {@code INSERT ... ON CONFLICT ... DO UPDATE}：覆盖时
 * {@code created_by} 保持首次写入人不变（与 MySQL 版行为一致）。</p>
 */
public class PostgresTableRemarkDao implements TableRemarkDao {

    private final JdbcTemplate jdbc;

    public PostgresTableRemarkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TableRemark> MAPPER = (rs, i) -> {
        TableRemark r = new TableRemark();
        r.setId(rs.getLong("id"));
        r.setInstanceId(rs.getLong("instance_id"));
        r.setSchemaName(rs.getString("schema_name"));
        r.setTableName(rs.getString("table_name"));
        r.setRemark(rs.getString("remark"));
        r.setCreatedBy(rs.getLong("created_by"));
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        r.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return r;
    };

    @Override
    public List<TableRemark> findByInstanceAndSchema(long instanceId, String schemaName) {
        return jdbc.query(
                "SELECT * FROM t_table_remark WHERE instance_id=? AND schema_name=? ORDER BY table_name",
                MAPPER, instanceId, schemaName);
    }

    @Override
    public int upsert(TableRemark remark) {
        LocalDateTime now = remark.getUpdatedAt() != null ? remark.getUpdatedAt() : LocalDateTime.now();
        return jdbc.update(
                "INSERT INTO t_table_remark (instance_id, schema_name, table_name, remark, "
                        + "created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (instance_id, schema_name, table_name) "
                        + "DO UPDATE SET remark=EXCLUDED.remark, updated_at=EXCLUDED.updated_at",
                remark.getInstanceId(), remark.getSchemaName(), remark.getTableName(),
                remark.getRemark(), remark.getCreatedBy(),
                Timestamp.valueOf(remark.getCreatedAt() != null ? remark.getCreatedAt() : now),
                Timestamp.valueOf(now));
    }

    @Override
    public int delete(long instanceId, String schemaName, String tableName) {
        return jdbc.update(
                "DELETE FROM t_table_remark WHERE instance_id=? AND schema_name=? AND table_name=?",
                instanceId, schemaName, tableName);
    }
}
