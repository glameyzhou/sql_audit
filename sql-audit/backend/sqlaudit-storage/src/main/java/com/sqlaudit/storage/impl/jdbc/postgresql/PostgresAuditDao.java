package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * chain_head / audit_log 的 PostgreSQL 真实现
 * （DDL 审计日志与 hash 链表；内存版语义基准 {@code InMemoryAuditDao}，SQL 逐句对照
 * MySQL 版 {@code MySqlAuditDao}）。
 *
 * <p><strong>hash 链写入流程（README §4.1 单写入点，语义与内存版一致）：</strong>
 * {@code lockChainHead()} → 取 last_seq/last_hash → 调用方算本条 hash →
 * {@code insert()} → {@code updateChainHead()}。</p>
 *
 * <p><strong>锁模型：</strong>{@code lockChainHead} 用 {@code SELECT ... WHERE id=1 FOR UPDATE}
 * 行锁串行化并发 append——<strong>必须在调用方事务内调用才有意义</strong>，事务提交自动释放行锁
 * （内存版用 ReentrantLock 模拟同款等待语义，并自带 forceUnlockChain 测试兜底，JDBC 版
 * 行锁由事务边界管理，不需要该辅助）。</p>
 *
 * <p><strong>与 MySQL 版的方言差异：</strong>{@code detail} 为 BYTEA（byte[]）AES-GCM 密文，
 * 原样读写；自增 id 回填改为 {@code INSERT ... RETURNING id}；PG 无
 * {@code ON UPDATE CURRENT_TIMESTAMP}，{@code updateChainHead} 显式置 {@code updated_at=CURRENT_TIMESTAMP}
 * 以保持「链头更新时间」语义。seq 由链头行锁内分配，连续无空洞。事务边界由调用方保证，本类不开事务。</p>
 */
public class PostgresAuditDao implements AuditDao {

    private final JdbcTemplate jdbc;

    public PostgresAuditDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChainHead> CHAIN_MAPPER = (rs, i) -> {
        ChainHead head = new ChainHead();
        head.setId(rs.getInt("id"));
        head.setLastSeq(rs.getLong("last_seq"));
        head.setLastHash(rs.getString("last_hash"));
        Timestamp updated = rs.getTimestamp("updated_at");
        head.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return head;
    };

    private static final RowMapper<AuditLog> LOG_MAPPER = (rs, i) -> {
        AuditLog log = new AuditLog();
        log.setId(rs.getLong("id"));
        log.setSeq(rs.getLong("seq"));
        log.setActorId(rs.getLong("actor_id"));
        log.setActorName(rs.getString("actor_name"));
        log.setActorRole(rs.getString("actor_role"));
        log.setAction(rs.getString("action"));
        log.setTargetType(rs.getString("target_type"));
        log.setTargetId(rs.getString("target_id"));
        long instanceId = rs.getLong("instance_id");
        log.setInstanceId(rs.wasNull() ? null : instanceId);
        log.setDetail(rs.getBytes("detail"));
        log.setResult(rs.getString("result"));
        log.setClientIp(rs.getString("client_ip"));
        log.setUserAgent(rs.getString("user_agent"));
        log.setTraceId(rs.getString("trace_id"));
        log.setPrevHash(rs.getString("prev_hash"));
        log.setHash(rs.getString("hash"));
        Timestamp created = rs.getTimestamp("created_at");
        log.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return log;
    };

    @Override
    public ChainHead lockChainHead() {
        // FOR UPDATE 行锁：串行化并发 append，seq 连续无空洞；须在调用方事务内调用
        return jdbc.queryForObject(
                "SELECT * FROM t_chain_head WHERE id=1 FOR UPDATE", CHAIN_MAPPER);
    }

    @Override
    public int insert(AuditLog log) {
        long id = jdbc.queryForObject(
                "INSERT INTO t_audit_log (seq, actor_id, actor_name, actor_role, action, target_type, "
                        + "target_id, instance_id, detail, result, client_ip, user_agent, trace_id, "
                        + "prev_hash, hash, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "RETURNING id",
                Long.class,
                log.getSeq(),
                log.getActorId(),
                log.getActorName(),
                log.getActorRole(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getInstanceId(),
                log.getDetail(),
                log.getResult(),
                log.getClientIp(),
                log.getUserAgent(),
                log.getTraceId(),
                log.getPrevHash(),
                log.getHash(),
                Timestamp.valueOf(log.getCreatedAt() != null
                        ? log.getCreatedAt() : LocalDateTime.now()));
        log.setId(id);
        return 1;
    }

    @Override
    public int updateChainHead(long lastSeq, String lastHash) {
        // PG 无 ON UPDATE CURRENT_TIMESTAMP，显式置 updated_at 保持链头更新时间语义
        return jdbc.update(
                "UPDATE t_chain_head SET last_seq=?, last_hash=?, updated_at=CURRENT_TIMESTAMP WHERE id=1",
                lastSeq, lastHash);
    }

    @Override
    public List<AuditLog> listBySeqRange(long fromSeq, long toSeq) {
        return jdbc.query(
                "SELECT * FROM t_audit_log WHERE seq>=? AND seq<=? ORDER BY seq",
                LOG_MAPPER, fromSeq, toSeq);
    }

    @Override
    public List<AuditLog> listBySeqRange(long fromSeq, long toSeq, int offset, int limit) {
        // 服务端分页：limit<0 视为取全部（PG 与 MySQL 同款 LIMIT ? OFFSET ? 语义）
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : limit;
        return jdbc.query(
                "SELECT * FROM t_audit_log WHERE seq>=? AND seq<=? ORDER BY seq LIMIT ? OFFSET ?",
                LOG_MAPPER, fromSeq, toSeq, safeLimit, offset);
    }

    @Override
    public long countBySeqRange(long fromSeq, long toSeq) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE seq>=? AND seq<=?", Long.class, fromSeq, toSeq);
    }

    /** 不加锁直接读链头快照（测试断言用，勿在 append 流程内替代 lockChainHead）。 */
    public ChainHead peekChainHead() {
        return jdbc.queryForObject(
                "SELECT * FROM t_chain_head WHERE id=1", CHAIN_MAPPER);
    }
}
