package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
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
 * ticket 的 MySQL 真实现（t_ticket 表，单实例工单）。
 *
 * <p><strong>SQL 原文加密</strong>：{@code sql_text} 为 {@code LONGBLOB}（密文），
 * 模型里是明文 {@code String}——写入前经 {@link SqlCipher} 加密（AES-GCM，随机 nonce，
 * {@code iv || 密文+tag}），读出后解密；null 透传（列可空）。库里无明文，
 * {@code statement_hash} 语义由调用方按明文计算，与本类无关。</p>
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code updateStatus} 条件更新（{@code WHERE status=fromStatus}），影响行数 0 =
 *       状态已被并发修改或工单不存在——乐观并发判定；{@code updated_at} 显式置为
 *       {@code changedAt}（审批时效 approval_ttl 的基准）；</li>
 *   <li>{@code listByCreator} / {@code listAll} / {@code listByInstanceIds} 按
 *       {@code created_at DESC, id DESC}（最新在前）；</li>
 *   <li>{@code listByStatus} 按 id 升序稳定返回（reaper / 到期扫描用）。</li>
 * </ul>
 */
public class MySqlTicketDao implements TicketDao {

    private final JdbcTemplate jdbc;
    private final SqlCipher cipher;

    public MySqlTicketDao(JdbcTemplate jdbc, SqlCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    private static final RowMapper<Ticket> MAPPER = (rs, i) -> {
        Ticket t = new Ticket();
        t.setId(rs.getLong("id"));
        t.setTitle(rs.getString("title"));
        t.setCreatorId(rs.getLong("creator_id"));
        t.setInstanceId(rs.getLong("instance_id"));
        t.setRiskLevel(RiskLevel.fromCode(rs.getString("risk_level")));
        t.setStatus(TicketStatus.fromCode(rs.getString("status")));
        t.setRollbackSummary(rs.getString("rollback_summary"));
        // sql_text 为 LONGBLOB 密文，不在 MAPPER 里读；由 decrypting() 包装统一解密
        t.setShardConfig(rs.getString("shard_config"));
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
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_ticket (title, creator_id, instance_id, risk_level, status, "
                            + "rollback_summary, sql_text, shard_config, trace_id, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, ticket.getTitle());
            ps.setLong(2, ticket.getCreatorId());
            ps.setLong(3, ticket.getInstanceId());
            ps.setString(4, CodedEnum.code(ticket.getRiskLevel()));
            ps.setString(5, CodedEnum.code(ticket.getStatus()));
            // rollback_summary 为 JSON 文本列，原样透传（可 null）
            ps.setString(6, ticket.getRollbackSummary());
            // LONGBLOB 密文列：明文 String → AES-GCM 密文（null 透传）
            ps.setBytes(7, cipher.encrypt(ticket.getSqlText()));
            ps.setString(8, ticket.getShardConfig());
            ps.setString(9, ticket.getTraceId());
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(10, Timestamp.valueOf(ticket.getCreatedAt() != null
                    ? ticket.getCreatedAt() : now));
            ps.setTimestamp(11, Timestamp.valueOf(ticket.getUpdatedAt() != null
                    ? ticket.getUpdatedAt() : now));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        ticket.setId(id);
        return id;
    }

    @Override
    public Ticket findById(long id) {
        List<Ticket> result = jdbc.query("SELECT * FROM t_ticket WHERE id=?", decrypting(), id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int updateStatus(long id, TicketStatus fromStatus, TicketStatus toStatus, LocalDateTime changedAt) {
        // 条件 UPDATE（乐观并发）：fromStatus 不匹配 → 0；updated_at 显式置为 changedAt
        return jdbc.update(
                "UPDATE t_ticket SET status=?, updated_at=? WHERE id=? AND status=?",
                CodedEnum.code(toStatus), Timestamp.valueOf(changedAt), id, CodedEnum.code(fromStatus));
    }

    @Override
    public List<Ticket> listByCreator(long creatorId) {
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE creator_id=? ORDER BY created_at DESC, id DESC",
                decrypting(), creatorId);
    }

    @Override
    public List<Ticket> listByCreator(long creatorId, int offset, int limit) {
        // 服务端分页：limit<0 视为取全部（避免 MySQL LIMIT 负数语法错误）
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE creator_id=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                decrypting(), creatorId, safeLimit, offset);
    }

    @Override
    public long countByCreator(long creatorId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_ticket WHERE creator_id=?", Long.class, creatorId);
    }

    @Override
    public List<Ticket> listAll() {
        return jdbc.query("SELECT * FROM t_ticket ORDER BY created_at DESC, id DESC", decrypting());
    }

    @Override
    public List<Ticket> listAll(int offset, int limit) {
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        return jdbc.query(
                "SELECT * FROM t_ticket ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                decrypting(), safeLimit, offset);
    }

    @Override
    public long countAll() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_ticket", Long.class);
    }

    @Override
    public List<Ticket> listByInstanceIds(List<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            // 空集合 = 数据范围为空，返回空列表（不等于「不过滤」）
            return List.of();
        }
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[instanceIds.size()];
        for (int i = 0; i < instanceIds.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
            args[i] = instanceIds.get(i);
        }
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE instance_id IN (" + in + ") ORDER BY created_at DESC, id DESC",
                decrypting(), args);
    }

    @Override
    public List<Ticket> listByInstanceIds(List<Long> instanceIds, int offset, int limit) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            // 空集合 = 数据范围为空，返回空列表（不等于「不过滤」）
            return List.of();
        }
        StringBuilder in = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>(instanceIds.size() + 2);
        for (int i = 0; i < instanceIds.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
            args.add(instanceIds.get(i));
        }
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        args.add(safeLimit);
        args.add(offset);
        return jdbc.query(
                "SELECT * FROM t_ticket WHERE instance_id IN (" + in
                        + ") ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                decrypting(), args.toArray());
    }

    @Override
    public long countByInstanceIds(List<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            // 空集合 = 数据范围为空，计数 0（不等于「不过滤」）
            return 0L;
        }
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[instanceIds.size()];
        for (int i = 0; i < instanceIds.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
            args[i] = instanceIds.get(i);
        }
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_ticket WHERE instance_id IN (" + in + ")", Long.class, args);
    }

    @Override
    public List<Ticket> listByStatus(TicketStatus status) {
        return jdbc.query("SELECT * FROM t_ticket WHERE status=? ORDER BY id",
                decrypting(), CodedEnum.code(status));
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

    /** 读查询共用：在 MAPPER 之上解密 {@code sql_text}（LONGBLOB 密文 → 明文 String）。 */
    private RowMapper<Ticket> decrypting() {
        return (rs, i) -> {
            Ticket t = MAPPER.mapRow(rs, i);
            t.setSqlText(cipher.decrypt(rs.getBytes("sql_text")));
            return t;
        };
    }
}
