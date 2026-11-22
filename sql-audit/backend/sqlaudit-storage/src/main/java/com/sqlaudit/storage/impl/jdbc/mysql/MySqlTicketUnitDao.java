package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ticket_unit 的 MySQL 真实现（设计文档 §3.3 协议 SQL 逐条对应）。
 *
 * <p><strong>协议语义与内存版严格一致</strong>（内存版 24 测试验证过的语义契约）：</p>
 * <ul>
 *   <li>所有写方法都是<strong>条件 UPDATE</strong>，影响行数 0 = 条件不满足（抢不到/已流转），
 *       不抛异常——调用方（引擎）按返回值分流；</li>
 *   <li>{@code claimUnit} 硬条件 {@code intent_written=0}（DDL CHECK 约束兜底不变式）；</li>
 *   <li>{@code confirmSuccess} 条件 {@code status='executing' AND executed_at IS NULL}
 *       ——幂等不靠唯一约束（同毫秒完成合法，DDL 注释已说明）；</li>
 *   <li>时间全部 {@code DATETIME(3)} 毫秒精度。</li>
 * </ul>
 *
 * <p>事务边界由调用方（Spring @Transactional）保证；本类不开启事务。</p>
 */
public class MySqlTicketUnitDao implements TicketUnitDao {

    private final JdbcTemplate jdbc;
    private final String tableName;

    /** 表名可注入（测试分表/多 schema 场景），默认 ticket_unit。 */
    public MySqlTicketUnitDao(JdbcTemplate jdbc) {
        this(jdbc, "t_ticket_unit");
    }

    public MySqlTicketUnitDao(JdbcTemplate jdbc, String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    private static final RowMapper<TicketUnit> MAPPER = (rs, i) -> map(rs);

    static TicketUnit map(ResultSet rs) throws SQLException {
        TicketUnit u = new TicketUnit();
        u.setId(rs.getLong("id"));
        u.setTicketId(rs.getLong("ticket_id"));
        u.setInstanceId(rs.getLong("instance_id"));
        u.setSchemaName(rs.getString("schema_name"));
        u.setObjectName(rs.getString("object_name"));
        u.setUnitOrder(rs.getInt("unit_order"));
        u.setStatement(rs.getBytes("statement"));
        u.setStatementHash(rs.getString("statement_hash"));
        u.setStatementType(rs.getString("statement_type"));
        u.setDialectSpecific(rs.getString("dialect_specific"));
        u.setStatus(rs.getString("status"));
        u.setLeaseOwner(rs.getString("lease_owner"));
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        u.setLeaseUntil(leaseUntil == null ? null : leaseUntil.toLocalDateTime());
        Timestamp heartbeat = rs.getTimestamp("heartbeat_at");
        u.setHeartbeatAt(heartbeat == null ? null : heartbeat.toLocalDateTime());
        u.setIntentWritten(rs.getBoolean("intent_written"));
        Timestamp executed = rs.getTimestamp("executed_at");
        u.setExecutedAt(executed == null ? null : executed.toLocalDateTime());
        u.setExecFingerprint(rs.getString("exec_fingerprint"));
        long preimage = rs.getLong("preimage_rows");
        u.setPreimageRows(rs.wasNull() ? null : preimage);
        long affected = rs.getLong("affected_rows");
        u.setAffectedRows(rs.wasNull() ? null : affected);
        u.setDdlFingerprint(rs.getString("ddl_fingerprint"));
        u.setBinlogStart(rs.getString("binlog_start"));
        u.setBinlogEnd(rs.getString("binlog_end"));
        u.setErrorMessage(rs.getString("error_message"));
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return u;
    }

    @Override
    public int claimUnit(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        // §3.3 抢占：硬条件 intent_written=0 + pending（无主）或租约过期（可重抢，
        // 原租约人崩溃后 lease_owner 仍在——过期即失权，与内存版语义一致）
        return jdbc.update(
                "UPDATE " + tableName + " SET status='leased', lease_owner=?, lease_until=?, heartbeat_at=? "
                        + "WHERE id=? AND intent_written=0 "
                        + "AND (status='pending' OR (status='leased' AND lease_until < ?))",
                leaseOwner, Timestamp.valueOf(leaseUntil), Timestamp.valueOf(heartbeatAt),
                id, Timestamp.valueOf(heartbeatAt));
    }

    @Override
    public int writeIntent(long id, LocalDateTime now) {
        // §3.3 预写 intent：事务提交后才连生产库；条件=status='leased' 且被本线程持有租约上下文
        // （lease 校验由引擎的执行锁保证时序，此处按协议只做状态条件）
        return jdbc.update(
                "UPDATE " + tableName + " SET status='executing', intent_written=1, heartbeat_at=? "
                        + "WHERE id=? AND status='leased' AND intent_written=0",
                Timestamp.valueOf(now), id);
    }

    @Override
    public int confirmSuccess(long id, long affectedRows, LocalDateTime executedAt) {
        // §3.3 幂等确认：条件 UPDATE 不靠唯一约束；executed_at 已非空 → 0（已确认过，返回成功即可）
        return jdbc.update(
                "UPDATE " + tableName + " SET status='success', affected_rows=?, executed_at=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status='executing' AND executed_at IS NULL",
                affectedRows, Timestamp.valueOf(executedAt), id);
    }

    @Override
    public int confirmFailed(long id, String errorMessage, LocalDateTime executedAt) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status='failed', error_message=?, executed_at=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status='executing'",
                errorMessage, Timestamp.valueOf(executedAt), id);
    }

    @Override
    public int markNeedsConfirm(long id, String reason) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status='needs_confirm', error_message=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status IN ('executing','leased')",
                reason, id);
    }

    @Override
    public int releaseLeaseToPending(long id) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status='pending', lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status='leased' AND intent_written=0",
                id);
    }

    @Override
    public int reclaimExpiredLease(LocalDateTime now) {
        // reaper 安全回收：未预写 intent 的过期租约回 pending
        return jdbc.update(
                "UPDATE " + tableName + " SET status='pending', lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE intent_written=0 AND lease_until < ? "
                        + "AND status IN ('pending','leased')",
                Timestamp.valueOf(now));
    }

    @Override
    public List<TicketUnit> listExecutingWithStaleHeartbeat(LocalDateTime heartbeatThreshold) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE status='executing' AND heartbeat_at < ? AND executed_at IS NULL "
                        + "ORDER BY id",
                MAPPER, Timestamp.valueOf(heartbeatThreshold));
    }

    @Override
    public TicketUnit findById(long id) {
        List<TicketUnit> result = jdbc.query(
                "SELECT * FROM " + tableName + " WHERE id=?", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<TicketUnit> listByFingerprintSince(String execFingerprint, LocalDateTime since) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE exec_fingerprint=? AND created_at >= ? ORDER BY id",
                MAPPER, execFingerprint, Timestamp.valueOf(since));
    }

    @Override
    public List<TicketUnit> listClaimable(LocalDateTime now, int limit) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE intent_written=0 "
                        + "AND (status='pending' OR (status='leased' AND lease_until < ?)) "
                        + "ORDER BY ticket_id, unit_order LIMIT ?",
                MAPPER, Timestamp.valueOf(now), limit);
    }

    @Override
    public int renewLease(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        return jdbc.update(
                "UPDATE " + tableName + " SET lease_until=?, heartbeat_at=? "
                        + "WHERE id=? AND status='executing' AND lease_owner=?",
                Timestamp.valueOf(leaseUntil), Timestamp.valueOf(heartbeatAt), id, leaseOwner);
    }

    /** 插入（测试与 workflow 建单用；自增 id 回填）。 */
    @Override
    public List<TicketUnit> listByTicket(long ticketId) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE ticket_id=? ORDER BY unit_order",
                MAPPER, ticketId);
    }

    @Override
    public List<TicketUnit> listByStatus(String status) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE status=? ORDER BY id",
                MAPPER, status);
    }

    public long insert(TicketUnit unit) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + tableName + " (ticket_id, instance_id, schema_name, object_name, "
                            + "unit_order, statement, statement_hash, statement_type, dialect_specific, "
                            + "status, intent_written, ddl_fingerprint, exec_fingerprint, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, unit.getTicketId());
            ps.setLong(2, unit.getInstanceId());
            ps.setString(3, unit.getSchemaName());
            ps.setString(4, unit.getObjectName());
            ps.setObject(5, unit.getUnitOrder());
            ps.setBytes(6, unit.getStatement());
            ps.setString(7, unit.getStatementHash());
            ps.setString(8, unit.getStatementType());
            ps.setString(9, unit.getDialectSpecific());
            ps.setString(10, unit.getStatus() == null ? "pending" : unit.getStatus());
            ps.setBoolean(11, Boolean.TRUE.equals(unit.getIntentWritten()));
            ps.setString(12, unit.getDdlFingerprint());
            ps.setString(13, unit.getExecFingerprint());
            ps.setTimestamp(14, Timestamp.valueOf(unit.getCreatedAt() != null
                    ? unit.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        unit.setId(id);
        return id;
    }

    /** 按状态计数（观测/测试用）。 */
    public int countByStatus(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE status=?", Integer.class, status);
        return count == null ? 0 : count;
    }
}
