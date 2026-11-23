package com.sqlaudit.storage.impl.jdbc.h2;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
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
 * ticket_unit 的 H2 真实现（设计文档 §3.3 协议 SQL 逐条对应，SQL 逐句对照
 * PostgreSQL 版 {@code PostgresTicketUnitDao}）。
 *
 * <p><strong>协议语义与内存版严格一致</strong>（内存版 24 测试验证过的语义契约）：</p>
 * <ul>
 *   <li>所有写方法都是<strong>条件 UPDATE</strong>，影响行数 0 = 条件不满足（抢不到/已流转），
 *       不抛异常——调用方（引擎）按返回值分流；</li>
 *   <li>{@code claimUnit} 硬条件 {@code intent_written=false}（DDL CHECK 约束兜底不变式）；</li>
 *   <li>{@code confirmSuccess} 条件 {@code status='executing' AND executed_at IS NULL}
 *       ——幂等不靠唯一约束（同毫秒完成合法，DDL 注释已说明）；</li>
 *   <li>时间全部 {@code TIMESTAMP(3)} 毫秒精度。</li>
 * </ul>
 *
 * <p><strong>与 PostgreSQL 版的方言差异：</strong>{@code intent_written} 为 BOOLEAN，SQL 中
 * 布尔字面量写 {@code FALSE}；{@code dialect_specific} 为 JSON，写入用 {@code ? FORMAT JSON}
 * （H2 JSON 类型需 FORMAT JSON 解析为结构，否则按 JSON 字符串值存储）；自增 id 回填由
 * {@code INSERT ... RETURNING} 改为 {@code GeneratedKeyHolder}（H2 不支持 RETURNING）。</p>
 *
 * <p>事务边界由调用方（Spring @Transactional）保证；本类不开启事务。</p>
 */
public class H2TicketUnitDao implements TicketUnitDao {

    private final JdbcTemplate jdbc;
    private final String tableName;

    /** 表名可注入（测试分表/多 schema 场景），默认 t_ticket_unit。 */
    public H2TicketUnitDao(JdbcTemplate jdbc) {
        this(jdbc, "t_ticket_unit");
    }

    public H2TicketUnitDao(JdbcTemplate jdbc, String tableName) {
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
        u.setStatementType(StatementType.fromCode(rs.getString("statement_type")));
        u.setDialectSpecific(rs.getString("dialect_specific"));
        u.setStatus(UnitStatus.fromCode(rs.getString("status")));
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
        // §3.3 抢占：硬条件 intent_written=false + pending（无主）或租约过期（可重抢，
        // 原租约人崩溃后 lease_owner 仍在——过期即失权，与内存版语义一致）
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, lease_owner=?, lease_until=?, heartbeat_at=? "
                        + "WHERE id=? AND intent_written=FALSE "
                        + "AND (status=? OR (status=? AND lease_until < ?))",
                UnitStatus.LEASED.getCode(), leaseOwner, Timestamp.valueOf(leaseUntil), Timestamp.valueOf(heartbeatAt),
                id, UnitStatus.PENDING.getCode(), UnitStatus.LEASED.getCode(), Timestamp.valueOf(heartbeatAt));
    }

    @Override
    public int writeIntent(long id, LocalDateTime now) {
        // §3.3 预写 intent：事务提交后才连生产库；条件=status='leased' 且被本线程持有租约上下文
        // （lease 校验由引擎的执行锁保证时序，此处按协议只做状态条件）
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, intent_written=TRUE, heartbeat_at=? "
                        + "WHERE id=? AND status=? AND intent_written=FALSE",
                UnitStatus.EXECUTING.getCode(), Timestamp.valueOf(now), id, UnitStatus.LEASED.getCode());
    }

    @Override
    public int confirmSuccess(long id, long affectedRows, LocalDateTime executedAt) {
        // §3.3 幂等确认：条件 UPDATE 不靠唯一约束；executed_at 已非空 → 0（已确认过，返回成功即可）
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, affected_rows=?, executed_at=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status=? AND executed_at IS NULL",
                UnitStatus.SUCCESS.getCode(), affectedRows, Timestamp.valueOf(executedAt), id, UnitStatus.EXECUTING.getCode());
    }

    @Override
    public int confirmFailed(long id, String errorMessage, LocalDateTime executedAt) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, error_message=?, executed_at=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status=?",
                UnitStatus.FAILED.getCode(), errorMessage, Timestamp.valueOf(executedAt), id, UnitStatus.EXECUTING.getCode());
    }

    @Override
    public int markNeedsConfirm(long id, String reason) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, error_message=?, "
                        + "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status IN (?, ?)",
                UnitStatus.NEEDS_CONFIRM.getCode(), reason, id, UnitStatus.EXECUTING.getCode(), UnitStatus.LEASED.getCode());
    }

    @Override
    public int updateDdlFingerprint(long id, String fingerprint) {
        // §3.5：审批通过时采集结构指纹写入；执行前 worker 侧重算比对（R_SCHEMA_DRIFT）
        return jdbc.update(
                "UPDATE " + tableName + " SET ddl_fingerprint=? WHERE id=?",
                fingerprint, id);
    }

    @Override
    public int releaseLeaseToPending(long id) {
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE id=? AND status=? AND intent_written=FALSE",
                UnitStatus.PENDING.getCode(), id, UnitStatus.LEASED.getCode());
    }

    @Override
    public int reclaimExpiredLease(LocalDateTime now) {
        // reaper 安全回收：未预写 intent 的过期租约回 pending
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL "
                        + "WHERE intent_written=FALSE AND lease_until < ? "
                        + "AND status IN (?, ?)",
                UnitStatus.PENDING.getCode(), Timestamp.valueOf(now), UnitStatus.PENDING.getCode(), UnitStatus.LEASED.getCode());
    }

    @Override
    public List<TicketUnit> listExecutingWithStaleHeartbeat(LocalDateTime heartbeatThreshold) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE status=? AND heartbeat_at < ? AND executed_at IS NULL "
                        + "ORDER BY id",
                MAPPER, UnitStatus.EXECUTING.getCode(), Timestamp.valueOf(heartbeatThreshold));
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
                "SELECT * FROM " + tableName + " WHERE intent_written=FALSE "
                        + "AND (status=? OR (status=? AND lease_until < ?)) "
                        + "ORDER BY ticket_id, unit_order LIMIT ?",
                MAPPER, UnitStatus.PENDING.getCode(), UnitStatus.LEASED.getCode(), Timestamp.valueOf(now), limit);
    }

    @Override
    public int renewLease(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        return jdbc.update(
                "UPDATE " + tableName + " SET lease_until=?, heartbeat_at=? "
                        + "WHERE id=? AND status=? AND lease_owner=?",
                Timestamp.valueOf(leaseUntil), Timestamp.valueOf(heartbeatAt), id, UnitStatus.EXECUTING.getCode(), leaseOwner);
    }

    @Override
    public List<TicketUnit> listByTicket(long ticketId) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE ticket_id=? ORDER BY unit_order",
                MAPPER, ticketId);
    }

    @Override
    public List<TicketUnit> listByStatus(UnitStatus status) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE status=? ORDER BY id",
                MAPPER, CodedEnum.code(status));
    }

    /** 插入（测试与 workflow 建单用；自增 id 回填）。 */
    @Override
    public long insert(TicketUnit unit) {
        KeyHolder kh = new GeneratedKeyHolder();
        boolean hasDialect = unit.getDialectSpecific() != null;
        String sql = "INSERT INTO " + tableName + " (ticket_id, instance_id, schema_name, object_name, "
                + "unit_order, statement, statement_hash, statement_type, dialect_specific, "
                + "status, intent_written, ddl_fingerprint, exec_fingerprint, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?," + (hasDialect ? "? FORMAT JSON" : "NULL") + ",?,?,?,?,?)";
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            int i = 1;
            ps.setLong(i++, unit.getTicketId());
            ps.setLong(i++, unit.getInstanceId());
            ps.setString(i++, unit.getSchemaName());
            ps.setString(i++, unit.getObjectName());
            ps.setObject(i++, unit.getUnitOrder());
            ps.setBytes(i++, unit.getStatement());
            ps.setString(i++, unit.getStatementHash());
            ps.setString(i++, CodedEnum.code(unit.getStatementType()));
            // JSON 列：dialect_specific 原样透传（可 null；null 用 SQL NULL 字面量）
            if (hasDialect) {
                ps.setString(i++, unit.getDialectSpecific());
            }
            ps.setString(i++, unit.getStatus() == null ? UnitStatus.PENDING.getCode() : unit.getStatus().getCode());
            ps.setBoolean(i++, Boolean.TRUE.equals(unit.getIntentWritten()));
            ps.setString(i++, unit.getDdlFingerprint());
            ps.setString(i++, unit.getExecFingerprint());
            ps.setTimestamp(i, Timestamp.valueOf(unit.getCreatedAt() != null
                    ? unit.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        unit.setId(id);
        return id;
    }

    /** 按状态计数（观测/测试用）。 */
    public int countByStatus(UnitStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE status=?", Integer.class, CodedEnum.code(status));
        return count == null ? 0 : count;
    }
}
