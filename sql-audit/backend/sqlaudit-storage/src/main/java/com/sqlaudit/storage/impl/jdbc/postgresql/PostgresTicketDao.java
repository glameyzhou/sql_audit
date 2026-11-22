package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ticket 的 PostgreSQL 真实现（DDL §5 ticket 表；内存版语义基准 {@code InMemoryTicketDao}，
 * SQL 逐句对照 MySQL 版 {@code MySqlTicketDao}）。
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code updateStatus} 条件更新（{@code WHERE status=fromStatus}），影响行数 0 =
 *       状态已被并发修改或工单不存在——乐观并发判定，与内存版 synchronized 检查+写入等价；</li>
 *   <li>{@code listByCreator} 按 {@code created_at DESC, id DESC}（内存版同款排序，最新在前）；</li>
 *   <li>{@code listByStatus} 按 id 升序稳定返回。</li>
 * </ul>
 *
 * <p><strong>JSON 列转换（DDL §5）：</strong>{@code instance_ids}（JSONB NOT NULL）为
 * {@code List&lt;Long&gt;} 与 JSON 文本的双向 Jackson 转换；{@code rollback_summary}
 * （JSONB NULL）模型即 JSON 字符串，原样透传（null 透传）。
 * 写入用 {@code setObject(idx, jsonText, Types.OTHER)} 让 PG 驱动识别为 jsonb。</p>
 *
 * <p><strong>与 MySQL 版的方言差异：</strong>自增 id 回填由 {@code GeneratedKeyHolder}
 * 改为 {@code INSERT ... RETURNING id}（PG JDBC 标准做法）。时间戳 {@code TIMESTAMP(3)}
 * 与 {@code LocalDateTime} 经 {@link Timestamp} 互转。事务边界由调用方保证，本类不开事务。</p>
 */
public class PostgresTicketDao implements TicketDao {

    /** instance_ids JSONB 列的 List&lt;Long&gt; 反序列化类型。 */
    private static final TypeReference<List<Long>> INSTANCE_IDS_TYPE = new TypeReference<>() {
    };

    /** Jackson 只做 List&lt;Long&gt;/文本转换，无自定义模块，静态共享线程安全。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public PostgresTicketDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Ticket> MAPPER = (rs, i) -> {
        Ticket t = new Ticket();
        t.setId(rs.getLong("id"));
        t.setTitle(rs.getString("title"));
        t.setCreatorId(rs.getLong("creator_id"));
        t.setInstanceIds(readInstanceIds(rs.getString("instance_ids")));
        t.setEnvironment(rs.getString("environment"));
        t.setRiskLevel(rs.getString("risk_level"));
        t.setStatus(rs.getString("status"));
        t.setRollbackSummary(rs.getString("rollback_summary"));
        Timestamp scheduled = rs.getTimestamp("scheduled_at");
        t.setScheduledAt(scheduled == null ? null : scheduled.toLocalDateTime());
        t.setTraceId(rs.getString("trace_id"));
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        t.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return t;
    };

    @Override
    public long insertTicket(Ticket ticket) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_ticket (title, creator_id, instance_ids, environment, risk_level, status, "
                            + "rollback_summary, scheduled_at, trace_id, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, ticket.getTitle());
            ps.setLong(2, ticket.getCreatorId());
            // JSONB 列：List<Long> 序列化为 JSON 文本；instanceIds 为 null 时 NOT NULL 约束报错
            ps.setObject(3, writeInstanceIds(ticket.getInstanceIds()), Types.OTHER);
            ps.setString(4, ticket.getEnvironment());
            ps.setString(5, ticket.getRiskLevel());
            ps.setString(6, ticket.getStatus());
            // rollback_summary 模型即 JSON 字符串，原样透传（可 null）
            ps.setObject(7, ticket.getRollbackSummary(), Types.OTHER);
            ps.setTimestamp(8, ticket.getScheduledAt() == null ? null : Timestamp.valueOf(ticket.getScheduledAt()));
            ps.setString(9, ticket.getTraceId());
            ps.setTimestamp(10, Timestamp.valueOf(ticket.getCreatedAt() != null
                    ? ticket.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(11, Timestamp.valueOf(ticket.getUpdatedAt() != null
                    ? ticket.getUpdatedAt() : LocalDateTime.now()));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        ticket.setId(id);
        return id;
    }

    @Override
    public Ticket findById(long id) {
        List<Ticket> result = jdbc.query("SELECT * FROM t_ticket WHERE id=?", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int updateStatus(long id, String fromStatus, String toStatus, LocalDateTime changedAt) {
        // 条件 UPDATE（乐观并发）：fromStatus 不匹配 → 0；updated_at 显式置为 changedAt
        // （approval_ttl 基准；PG 无 ON UPDATE CURRENT_TIMESTAMP，显式赋值语义与 MySQL 版一致）
        return jdbc.update(
                "UPDATE t_ticket SET status=?, updated_at=? WHERE id=? AND status=?",
                toStatus, Timestamp.valueOf(changedAt), id, fromStatus);
    }

    @Override
    public List<Ticket> listByCreator(long creatorId) {
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE creator_id=? ORDER BY created_at DESC, id DESC",
                MAPPER, creatorId);
    }

    @Override
    public List<Ticket> listByStatus(String status) {
        return jdbc.query("SELECT * FROM t_ticket WHERE status=? ORDER BY id", MAPPER, status);
    }

    // ---- JSON 列转换 ----

    private static String writeInstanceIds(List<Long> instanceIds) {
        if (instanceIds == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(instanceIds);
        } catch (Exception e) {
            // List<Long> 恒可序列化，此分支不可达
            throw new IllegalStateException("instance_ids JSON 序列化失败", e);
        }
    }

    private static List<Long> readInstanceIds(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, INSTANCE_IDS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("instance_ids JSON 解析失败: " + json, e);
        }
    }
}
