package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.crypto.SqlCipher;
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

/**
 * ticket_unit 的 PostgreSQL 真实现（README §4.2 协议 SQL 逐条对应，SQL 逐句对照
 * MySQL 版 {@code MySqlTicketUnitDao}）。
 *
 * <p><strong>SQL 语句加密</strong>：{@code statement} 为 {@code BYTEA}（密文），模型里是
 * 明文 {@code String}——写入前经 {@link SqlCipher} 加密、读出后解密（该列 NOT NULL）。
 * {@code statement_hash} 由调用方按<strong>明文</strong>计算传入，本类原样存取。</p>
 *
 * <p><strong>协议语义与内存版严格一致</strong>（内存版测试验证过的语义契约）：</p>
 * <ul>
 *   <li>所有写方法都是<strong>条件 UPDATE</strong>，影响行数 0 = 条件不满足（抢不到/已流转），
 *       不抛异常——调用方（引擎）按返回值分流；</li>
 *   <li>{@code claimUnit} 硬条件 {@code intent_written=FALSE}（DDL CHECK 约束兜底不变式）；</li>
 *   <li>{@code confirmSuccess} 条件 {@code status='executing' AND executed_at IS NULL}
 *       ——幂等不靠唯一约束（同毫秒完成合法，DDL 注释已说明）；</li>
 *   <li>时间全部 {@code TIMESTAMP(3)} 毫秒精度。</li>
 * </ul>
 *
 * <p><strong>与 MySQL 版的方言差异：</strong>{@code intent_written} 为 BOOLEAN，SQL 中
 * 布尔字面量写 {@code FALSE}（MySQL 版写 0）；自增 id 回填用 {@code INSERT ... RETURNING id}。</p>
 *
 * <p>事务边界由调用方（Spring @Transactional）保证；本类不开启事务。</p>
 */
public class PostgresTicketUnitDao implements TicketUnitDao {

    private final JdbcTemplate jdbc;
    private final SqlCipher cipher;
    private final String tableName;

    public PostgresTicketUnitDao(JdbcTemplate jdbc, SqlCipher cipher) {
        this(jdbc, cipher, "t_ticket_unit");
    }

    /** 表名可注入（测试分表/多 schema 场景）。 */
    public PostgresTicketUnitDao(JdbcTemplate jdbc, SqlCipher cipher, String tableName) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.tableName = tableName;
    }

    /** 除 statement（密文）外的列原样读取；statement 由 decrypting() 包装解密后设置。 */
    static TicketUnit map(ResultSet rs) throws SQLException {
        TicketUnit u = new TicketUnit();
        u.setId(rs.getLong("id"));
        u.setTicketId(rs.getLong("ticket_id"));
        u.setInstanceId(rs.getLong("instance_id"));
        u.setSchemaName(rs.getString("schema_name"));
        u.setObjectName(rs.getString("object_name"));
        u.setUnitOrder(rs.getInt("unit_order"));
        u.setStatementHash(rs.getString("statement_hash"));
        u.setStatementType(StatementType.fromCode(rs.getString("statement_type")));
        u.setStatus(UnitStatus.fromCode(rs.getString("status")));
        u.setLeaseOwner(rs.getString("lease_owner"));
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        u.setLeaseUntil(leaseUntil == null ? null : leaseUntil.toLocalDateTime());
        Timestamp heartbeat = rs.getTimestamp("heartbeat_at");
        u.setHeartbeatAt(heartbeat == null ? null : heartbeat.toLocalDateTime());
        u.setIntentWritten(rs.getBoolean("intent_written"));
        Timestamp executed = rs.getTimestamp("executed_at");
        u.setExecutedAt(executed == null ? null : executed.toLocalDateTime());
        long affected = rs.getLong("affected_rows");
        u.setAffectedRows(rs.wasNull() ? null : affected);
        u.setDdlFingerprint(rs.getString("ddl_fingerprint"));
        u.setErrorMessage(rs.getString("error_message"));
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return u;
    }

    @Override
    public int claimUnit(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        // README §4.2 抢占：硬条件 intent_written=FALSE + pending（无主）或租约过期（可重抢，
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
        // README §4.2 预写 intent：事务提交后才连生产库；条件 = status='leased'（租约持有校验
        // 由引擎的执行锁保证时序，此处按协议只做状态条件）
        return jdbc.update(
                "UPDATE " + tableName + " SET status=?, intent_written=TRUE, heartbeat_at=? "
                        + "WHERE id=? AND status=? AND intent_written=FALSE",
                UnitStatus.EXECUTING.getCode(), Timestamp.valueOf(now), id, UnitStatus.LEASED.getCode());
    }

    @Override
    public int confirmSuccess(long id, long affectedRows, LocalDateTime executedAt) {
        // README §4.2 幂等确认：条件 UPDATE 不靠唯一约束；executed_at 已非空 → 0（已确认过，返回成功即可）
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
        // README §4.2：审批通过时采集结构指纹写入；执行前 worker 侧重算比对（R_SCHEMA_DRIFT）
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
                decrypting(), UnitStatus.EXECUTING.getCode(), Timestamp.valueOf(heartbeatThreshold));
    }

    @Override
    public TicketUnit findById(long id) {
        List<TicketUnit> result = jdbc.query(
                "SELECT * FROM " + tableName + " WHERE id=?", decrypting(), id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<TicketUnit> listClaimable(LocalDateTime now, int limit) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE intent_written=FALSE "
                        + "AND (status=? OR (status=? AND lease_until < ?)) "
                        + "ORDER BY ticket_id, unit_order LIMIT ?",
                decrypting(), UnitStatus.PENDING.getCode(), UnitStatus.LEASED.getCode(),
                Timestamp.valueOf(now), limit);
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
                decrypting(), ticketId);
    }

    @Override
    public List<TicketUnit> listByStatus(UnitStatus status) {
        return jdbc.query(
                "SELECT * FROM " + tableName + " WHERE status=? ORDER BY id",
                decrypting(), CodedEnum.code(status));
    }

    @Override
    public long insert(TicketUnit unit) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + tableName + " (ticket_id, instance_id, schema_name, object_name, "
                            + "unit_order, statement, statement_hash, statement_type, "
                            + "status, intent_written, ddl_fingerprint, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
            ps.setLong(1, unit.getTicketId());
            ps.setLong(2, unit.getInstanceId());
            ps.setString(3, unit.getSchemaName());
            ps.setString(4, unit.getObjectName());
            ps.setObject(5, unit.getUnitOrder());
            // BYTEA 密文列：明文 String → AES-GCM 密文
            ps.setBytes(6, cipher.encrypt(unit.getStatement()));
            ps.setString(7, unit.getStatementHash());
            ps.setString(8, CodedEnum.code(unit.getStatementType()));
            ps.setString(9, unit.getStatus() == null ? UnitStatus.PENDING.getCode() : unit.getStatus().getCode());
            ps.setBoolean(10, Boolean.TRUE.equals(unit.getIntentWritten()));
            ps.setString(11, unit.getDdlFingerprint());
            ps.setTimestamp(12, Timestamp.valueOf(unit.getCreatedAt() != null
                    ? unit.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        unit.setId(id);
        return id;
    }

    /** 按状态计数（观测/测试用）。 */
    public int countByStatus(UnitStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE status=?", Integer.class, CodedEnum.code(status));
        return count == null ? 0 : count;
    }

    /** 读查询共用：在 map 之上解密 {@code statement}（BYTEA 密文 → 明文 String）。 */
    private RowMapper<TicketUnit> decrypting() {
        return (rs, i) -> {
            TicketUnit u = map(rs);
            u.setStatement(cipher.decrypt(rs.getBytes("statement")));
            return u;
        };
    }
}
