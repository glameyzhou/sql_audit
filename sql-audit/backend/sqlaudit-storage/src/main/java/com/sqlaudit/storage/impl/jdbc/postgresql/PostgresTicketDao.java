package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * ticket 的 PostgreSQL 真实现（t_ticket 表，单实例工单，SQL 逐句对照 MySQL 版
 * {@code MySqlTicketDao}）。
 *
 * <p><strong>SQL 原文加密</strong>：{@code sql_text} 为 {@code BYTEA}（密文），模型里是
 * 明文 {@code String}——写入前经 {@link SqlCipher} 加密（AES-GCM，随机 nonce，
 * {@code iv || 密文+tag}），读出后解密；null 透传（列可空）。库里无明文。</p>
 *
 * <p><strong>与 MySQL 版的方言差异：</strong>自增 id 回填用 {@code INSERT ... RETURNING id}；
 * PG 无 {@code ON UPDATE}，本类所有写路径都显式维护 {@code updated_at}。</p>
 */
public class PostgresTicketDao implements TicketDao {

    private final JdbcTemplate jdbc;
    private final SqlCipher cipher;

    public PostgresTicketDao(JdbcTemplate jdbc, SqlCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** 除 sql_text（密文）外的列原样读取；sql_text 由 decrypting() 包装解密后设置。 */
    private static final RowMapper<Ticket> MAPPER = (rs, i) -> {
        Ticket t = new Ticket();
        t.setId(rs.getLong("id"));
        t.setTitle(rs.getString("title"));
        t.setCreatorId(rs.getLong("creator_id"));
        t.setInstanceId(rs.getLong("instance_id"));
        t.setRiskLevel(RiskLevel.fromCode(rs.getString("risk_level")));
        t.setStatus(TicketStatus.fromCode(rs.getString("status")));
        t.setRollbackSummary(rs.getString("rollback_summary"));
        // sql_text 为 BYTEA 密文，不在 MAPPER 里读；由 decrypting() 包装统一解密
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
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_ticket (title, creator_id, instance_id, risk_level, status, "
                            + "rollback_summary, sql_text, shard_config, trace_id, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, ticket.getTitle());
            ps.setLong(2, ticket.getCreatorId());
            ps.setLong(3, ticket.getInstanceId());
            ps.setString(4, CodedEnum.code(ticket.getRiskLevel()));
            ps.setString(5, CodedEnum.code(ticket.getStatus()));
            // rollback_summary 为 JSON 文本列（TEXT），原样透传（可 null）
            ps.setString(6, ticket.getRollbackSummary());
            // BYTEA 密文列：明文 String → AES-GCM 密文（null 透传）
            ps.setBytes(7, cipher.encrypt(ticket.getSqlText()));
            ps.setString(8, ticket.getShardConfig());
            ps.setString(9, ticket.getTraceId());
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(10, Timestamp.valueOf(ticket.getCreatedAt() != null
                    ? ticket.getCreatedAt() : now));
            ps.setTimestamp(11, Timestamp.valueOf(ticket.getUpdatedAt() != null
                    ? ticket.getUpdatedAt() : now));
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
        List<Ticket> result = jdbc.query("SELECT * FROM t_ticket WHERE id=?", decrypting(), id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int updateStatus(long id, TicketStatus fromStatus, TicketStatus toStatus, LocalDateTime changedAt) {
        // 条件 UPDATE（乐观并发）：fromStatus 不匹配 → 0；updated_at 显式置为 changedAt
        // （approval_ttl 基准）
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
        // 服务端分页：limit<0 视为取全部（PG 与 MySQL 同款 LIMIT ? OFFSET ? 语义）
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

    /** 读查询共用：在 MAPPER 之上解密 {@code sql_text}（BYTEA 密文 → 明文 String）。 */
    private RowMapper<Ticket> decrypting() {
        return (rs, i) -> {
            Ticket t = MAPPER.mapRow(rs, i);
            t.setSqlText(cipher.decrypt(rs.getBytes("sql_text")));
            return t;
        };
    }
}
