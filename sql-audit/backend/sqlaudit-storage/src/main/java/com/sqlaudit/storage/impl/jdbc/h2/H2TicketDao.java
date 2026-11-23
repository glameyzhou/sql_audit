package com.sqlaudit.storage.impl.jdbc.h2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * ticket 的 H2 真实现（DDL §5 ticket 表；内存版语义基准 {@code InMemoryTicketDao}，
 * SQL 逐句对照 PostgreSQL 版 {@code PostgresTicketDao}，两者方言最接近）。
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code updateStatus} 条件更新（{@code WHERE status=fromStatus}），影响行数 0 =
 *       状态已被并发修改或工单不存在——乐观并发判定，与内存版 synchronized 检查+写入等价；</li>
 *   <li>{@code listByCreator} 按 {@code created_at DESC, id DESC}（内存版同款排序，最新在前）；</li>
 *   <li>{@code listByStatus} 按 id 升序稳定返回。</li>
 * </ul>
 *
 * <p><strong>JSON 列转换（DDL §5）：</strong>{@code instance_ids}（JSON NOT NULL）为
 * {@code List&lt;Long&gt;} 与 JSON 文本的双向 Jackson 转换；{@code rollback_summary}
 * （JSON NULL）模型即 JSON 字符串，原样透传（null 透传）。</p>
 *
 * <p><strong>与 PostgreSQL 版的方言差异：</strong>H2（MODE=PostgreSQL）不支持
 * {@code INSERT ... RETURNING}，自增 id 回填改用 {@code GeneratedKeyHolder}
 * （{@code Statement.RETURN_GENERATED_KEYS}）；JSON 列用 {@code ? FORMAT JSON} 写入——
 * H2 的 JSON 类型对普通字符串参数会按 JSON 字符串值存储（读出带引号），须 FORMAT JSON
 * 解析为 JSON 结构才能字节级透传（H2 JSON 不像 PG jsonb 那样规范化文本格式）。
 * 时间戳 {@code TIMESTAMP(3)} 与 {@code LocalDateTime} 经 {@link Timestamp} 互转。
 * 事务边界由调用方保证，本类不开事务。</p>
 */
public class H2TicketDao implements TicketDao {

    /** instance_ids JSON 列的 List&lt;Long&gt; 反序列化类型。 */
    private static final TypeReference<List<Long>> INSTANCE_IDS_TYPE = new TypeReference<>() {
    };

    /** Jackson 只做 List&lt;Long&gt;/文本转换，无自定义模块，静态共享线程安全。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public H2TicketDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Ticket> MAPPER = (rs, i) -> {
        Ticket t = new Ticket();
        t.setId(rs.getLong("id"));
        t.setTitle(rs.getString("title"));
        t.setCreatorId(rs.getLong("creator_id"));
        t.setInstanceIds(readInstanceIds(rs.getString("instance_ids")));
        t.setEnvironment(Environment.fromCode(rs.getString("environment")));
        t.setRiskLevel(RiskLevel.fromCode(rs.getString("risk_level")));
        t.setStatus(TicketStatus.fromCode(rs.getString("status")));
        t.setRollbackSummary(rs.getString("rollback_summary"));
        t.setSqlText(rs.getString("sql_text"));
        t.setShardConfig(rs.getString("shard_config"));
        Timestamp scheduled = rs.getTimestamp("scheduled_at");
        t.setScheduledAt(scheduled == null ? null : scheduled.toLocalDateTime());
        t.setTraceId(rs.getString("trace_id"));
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        t.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        t.setNeedsSecondConfirm(rs.getBoolean("needs_second_confirm"));
        t.setConfirmedBy(rs.getObject("confirmed_by", Long.class));
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        t.setConfirmedAt(confirmedAt == null ? null : confirmedAt.toLocalDateTime());
        return t;
    };

    @Override
    public long insertTicket(Ticket ticket) {
        KeyHolder kh = new GeneratedKeyHolder();
        boolean hasSummary = ticket.getRollbackSummary() != null;
        String sql = "INSERT INTO t_ticket (title, creator_id, instance_ids, environment, risk_level, status, "
                + "rollback_summary, scheduled_at, trace_id, created_at, updated_at, sql_text, shard_config) "
                + "VALUES (?,?,? FORMAT JSON,?,?,?," + (hasSummary ? "? FORMAT JSON" : "NULL")
                + ",?,?,?,?,?,?)";
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            int i = 1;
            ps.setString(i++, ticket.getTitle());
            ps.setLong(i++, ticket.getCreatorId());
            // JSON 列：List<Long> 序列化为 JSON 文本；instanceIds 为 null 时 NOT NULL 约束报错
            ps.setString(i++, writeInstanceIds(ticket.getInstanceIds()));
            ps.setString(i++, CodedEnum.code(ticket.getEnvironment()));
            ps.setString(i++, CodedEnum.code(ticket.getRiskLevel()));
            ps.setString(i++, CodedEnum.code(ticket.getStatus()));
            // rollback_summary 模型即 JSON 字符串，原样透传（可 null；null 用 SQL NULL 字面量）
            if (hasSummary) {
                ps.setString(i++, ticket.getRollbackSummary());
            }
            ps.setTimestamp(i++, ticket.getScheduledAt() == null ? null : Timestamp.valueOf(ticket.getScheduledAt()));
            ps.setString(i++, ticket.getTraceId());
            ps.setTimestamp(i++, Timestamp.valueOf(ticket.getCreatedAt() != null
                    ? ticket.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(i, Timestamp.valueOf(ticket.getUpdatedAt() != null
                    ? ticket.getUpdatedAt() : LocalDateTime.now()));
            ps.setString(i + 1, ticket.getSqlText());
            ps.setString(i + 2, ticket.getShardConfig());
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        ticket.setId(id);
        return id;
    }

    @Override
    public Ticket findById(long id) {
        List<Ticket> result = jdbc.query("SELECT * FROM t_ticket WHERE id=?", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int updateStatus(long id, TicketStatus fromStatus, TicketStatus toStatus, LocalDateTime changedAt) {
        // 条件 UPDATE（乐观并发）：fromStatus 不匹配 → 0；updated_at 显式置为 changedAt
        // （approval_ttl 基准；H2 无 ON UPDATE CURRENT_TIMESTAMP，显式赋值语义与 PG 版一致）
        return jdbc.update(
                "UPDATE t_ticket SET status=?, updated_at=? WHERE id=? AND status=?",
                CodedEnum.code(toStatus), Timestamp.valueOf(changedAt), id, CodedEnum.code(fromStatus));
    }

    @Override
    public List<Ticket> listByCreator(long creatorId) {
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE creator_id=? ORDER BY created_at DESC, id DESC",
                MAPPER, creatorId);
    }

    @Override
    public List<Ticket> listByStatus(TicketStatus status) {
        return jdbc.query("SELECT * FROM t_ticket WHERE status=? ORDER BY id", MAPPER, CodedEnum.code(status));
    }

    @Override
    public int markNeedsSecondConfirm(long id, boolean required) {
        return jdbc.update("UPDATE t_ticket SET needs_second_confirm=? WHERE id=?", required, id);
    }

    @Override
    public int updateSecondConfirm(long id, Long confirmedBy, LocalDateTime confirmedAt) {
        return jdbc.update("UPDATE t_ticket SET confirmed_by=?, confirmed_at=? WHERE id=?",
                confirmedBy, confirmedAt == null ? null : Timestamp.valueOf(confirmedAt), id);
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
