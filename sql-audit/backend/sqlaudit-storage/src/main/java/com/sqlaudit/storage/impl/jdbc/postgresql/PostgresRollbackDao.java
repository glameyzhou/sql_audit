package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * rollback_sql / rollback_statement 的 PostgreSQL 真实现（SQL 逐句对照 MySQL 版
 * {@code MySqlRollbackDao}）。
 *
 * <p><strong>反向语句加密</strong>：{@code reverse_sql} / {@code value_params} 为
 * {@code BYTEA}（密文）——反向语句携带的旧值参数就是前镜像生产数据，这是 SQL 加密的
 * 主要动机。模型里是明文 {@code String}，写入前经 {@link SqlCipher} 加密、读出后解密
 * （{@code valueParams} 列可空，null 透传）。</p>
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code insertRollbackSql}：uk_rollback_unit(unit_id) 唯一冲突（{@link DuplicateKeyException}）
 *       返回 0，不抛异常；成功回填自增 id 返回 1；</li>
 *   <li>{@code insertRollbackStatement}：uk_rollback_stmt_order(rollback_id, stmt_order)
 *       唯一冲突同理返回 0；</li>
 *   <li>{@code listStatements} 按 {@code stmt_order} 升序 + {@code LIMIT ? OFFSET ?} 分批流式读取
 *       （内存版 subList(offset, offset+limit) 同款语义；limit&lt;0 视为取全部）；</li>
 *   <li>{@code markStatementApplied} 条件更新 {@code WHERE id=? AND applied_at IS NULL}：
 *       逐条幂等回放，重复回放返回 0 且不覆盖首次时间戳；</li>
 *   <li>{@code updateStatus} 无条件更新 status 与 unavailable_reason（内存版同款）。</li>
 * </ul>
 */
public class PostgresRollbackDao implements RollbackDao {

    private final JdbcTemplate jdbc;
    private final SqlCipher cipher;

    public PostgresRollbackDao(JdbcTemplate jdbc, SqlCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    private static final RowMapper<RollbackSql> SQL_MAPPER = (rs, i) -> {
        RollbackSql r = new RollbackSql();
        r.setId(rs.getLong("id"));
        r.setUnitId(rs.getLong("unit_id"));
        r.setStmtCount(rs.getInt("stmt_count"));
        r.setTotalBytes(rs.getLong("total_bytes"));
        r.setGenerator(RollbackGenerator.fromCode(rs.getString("generator")));
        r.setStatus(RollbackStatus.fromCode(rs.getString("status")));
        r.setCoverage(RollbackCoverage.fromCode(rs.getString("coverage")));
        r.setUnavailableReason(rs.getString("unavailable_reason"));
        r.setPreimageHash(rs.getString("preimage_hash"));
        Timestamp expires = rs.getTimestamp("expires_at");
        r.setExpiresAt(expires == null ? null : expires.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return r;
    };

    /** 除 reverse_sql / value_params（密文）外的列原样读取。 */
    private static final RowMapper<RollbackStatement> STMT_MAPPER = (rs, i) -> {
        RollbackStatement s = new RollbackStatement();
        s.setId(rs.getLong("id"));
        s.setRollbackId(rs.getLong("rollback_id"));
        s.setStmtOrder(rs.getInt("stmt_order"));
        Timestamp applied = rs.getTimestamp("applied_at");
        s.setAppliedAt(applied == null ? null : applied.toLocalDateTime());
        return s;
    };

    @Override
    public int insertRollbackSql(RollbackSql rollbackSql) {
        try {
            Integer affected = jdbc.query(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_rollback_sql (unit_id, stmt_count, total_bytes, generator, status, "
                                + "coverage, unavailable_reason, preimage_hash, expires_at, created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id");
                ps.setLong(1, rollbackSql.getUnitId());
                // null 按 DDL DEFAULT 0 写入（内存版存 null，DDL 为 NOT NULL DEFAULT 0）
                ps.setInt(2, rollbackSql.getStmtCount() == null ? 0 : rollbackSql.getStmtCount());
                ps.setLong(3, rollbackSql.getTotalBytes() == null ? 0L : rollbackSql.getTotalBytes());
                ps.setString(4, CodedEnum.code(rollbackSql.getGenerator()));
                ps.setString(5, CodedEnum.code(rollbackSql.getStatus()));
                ps.setString(6, CodedEnum.code(rollbackSql.getCoverage()));
                ps.setString(7, rollbackSql.getUnavailableReason());
                ps.setString(8, rollbackSql.getPreimageHash());
                ps.setTimestamp(9, rollbackSql.getExpiresAt() == null
                        ? null : Timestamp.valueOf(rollbackSql.getExpiresAt()));
                ps.setTimestamp(10, Timestamp.valueOf(rollbackSql.getCreatedAt() != null
                        ? rollbackSql.getCreatedAt() : LocalDateTime.now()));
                return ps;
            }, rs -> {
                if (rs.next()) {
                    rollbackSql.setId(rs.getLong(1));
                    return 1;
                }
                throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
            });
            return affected == null ? 0 : affected;
        } catch (DuplicateKeyException e) {
            return 0; // uk_rollback_unit(unit_id) 冲突
        }
    }

    @Override
    public int insertRollbackStatement(RollbackStatement statement) {
        try {
            Integer inserted = jdbc.query(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_rollback_statement (rollback_id, stmt_order, reverse_sql, value_params) "
                                + "VALUES (?,?,?,?) RETURNING id");
                ps.setLong(1, statement.getRollbackId());
                ps.setInt(2, statement.getStmtOrder());
                // BYTEA 密文列：明文 String → AES-GCM 密文（value_params 可空，null 透传）
                ps.setBytes(3, cipher.encrypt(statement.getReverseSql()));
                ps.setBytes(4, cipher.encrypt(statement.getValueParams()));
                return ps;
            }, rs -> {
                if (rs.next()) {
                    statement.setId(rs.getLong(1));
                    return 1;
                }
                throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
            });
            return inserted == null ? 0 : inserted;
        } catch (DuplicateKeyException e) {
            return 0; // uk_rollback_stmt_order(rollback_id, stmt_order) 冲突
        }
    }

    @Override
    public List<RollbackStatement> listStatements(long rollbackId, int offset, int limit) {
        // 分批流式读取：limit<0 视为取全部（内存版同款）
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        return jdbc.query(
                "SELECT * FROM t_rollback_statement WHERE rollback_id=? ORDER BY stmt_order LIMIT ? OFFSET ?",
                decrypting(), rollbackId, safeLimit, offset);
    }

    @Override
    public int markStatementApplied(long statementId, LocalDateTime appliedAt) {
        // 条件 UPDATE ... WHERE id=? AND applied_at IS NULL：逐条幂等，重复回放返回 0
        return jdbc.update(
                "UPDATE t_rollback_statement SET applied_at=? WHERE id=? AND applied_at IS NULL",
                Timestamp.valueOf(appliedAt), statementId);
    }

    @Override
    public int updateStatus(long id, RollbackStatus status, String unavailableReason) {
        // 无条件更新（内存版同款，无 fromStatus 条件）；unavailable_reason 允许写 null
        return jdbc.update(
                "UPDATE t_rollback_sql SET status=?, unavailable_reason=? WHERE id=?",
                CodedEnum.code(status), unavailableReason, id);
    }

    @Override
    public RollbackSql findByUnitId(long unitId) {
        List<RollbackSql> result = jdbc.query(
                "SELECT * FROM t_rollback_sql WHERE unit_id=?", SQL_MAPPER, unitId);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public RollbackSql findById(long id) {
        List<RollbackSql> result = jdbc.query(
                "SELECT * FROM t_rollback_sql WHERE id=?", SQL_MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<RollbackSql> listAll() {
        return jdbc.query("SELECT * FROM t_rollback_sql ORDER BY id DESC", SQL_MAPPER);
    }

    @Override
    public List<RollbackSql> listAll(int offset, int limit) {
        // 服务端分页：limit<0 视为取全部（PG 与 MySQL 同款 LIMIT ? OFFSET ? 语义）
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        return jdbc.query(
                "SELECT * FROM t_rollback_sql ORDER BY id DESC LIMIT ? OFFSET ?",
                SQL_MAPPER, safeLimit, offset);
    }

    @Override
    public long countAll() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_rollback_sql", Long.class);
    }

    /** 读查询共用：在 STMT_MAPPER 之上解密两条密文列。 */
    private RowMapper<RollbackStatement> decrypting() {
        return (rs, i) -> {
            RollbackStatement s = STMT_MAPPER.mapRow(rs, i);
            s.setReverseSql(cipher.decrypt(rs.getBytes("reverse_sql")));
            s.setValueParams(cipher.decrypt(rs.getBytes("value_params")));
            return s;
        };
    }
}
