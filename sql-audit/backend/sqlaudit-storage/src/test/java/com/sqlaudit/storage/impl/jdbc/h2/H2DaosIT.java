package com.sqlaudit.storage.impl.jdbc.h2;

import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;
import com.sqlaudit.storage.model.ExecLock;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.Ticket;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 其余 5 个 JDBC DAO 对 H2 内存库的集成测试，语义对照各自内存版测试，
 * 断言逐条搬自 PostgreSQL 版 {@code PostgresDaosIT}：
 * <ul>
 *   <li>{@link H2TicketDao}：updateStatus 乐观并发（含并发竞争只有一个成功）、JSON 列双向转换；</li>
 *   <li>{@link H2ApprovalDao}：updateResult 条件更新（PENDING 终态只允许一次）；</li>
 *   <li>{@link H2RollbackDao}：唯一键冲突返回 0、markStatementApplied 幂等 0；</li>
 *   <li>{@link H2ExecLockDao}：tryAcquire 二次 false、条件 release、cleanupExpired；</li>
 *   <li>{@link H2AuditDao}：FOR UPDATE 行锁下 seq 递增无空洞、链头推进、listBySeqRange
 *       ——事务边界由调用方管理，测试用 TransactionTemplate 包裹验证。</li>
 * </ul>
 *
 * <p>与 PG 版测试的差异：H2 JSON 类型不做 jsonb 式文本规范化，rollback_summary
 * 断言为字节级透传（原始字符串）；H2 为嵌入式内存库，无需 Assumptions 跳过。</p>
 */
@Tag("it")
class H2DaosIT {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;

    private static H2TicketDao ticketDao;
    private static H2ApprovalDao approvalDao;
    private static H2RollbackDao rollbackDao;
    private static H2ExecLockDao execLockDao;
    private static H2AuditDao auditDao;

    @BeforeAll
    static void initDatabase() throws Exception {
        ds = H2TestSupport.buildDataSource();
        jdbc = new JdbcTemplate(ds);
        H2TestSupport.ensureSchema(ds);
        ticketDao = new H2TicketDao(jdbc);
        approvalDao = new H2ApprovalDao(jdbc);
        rollbackDao = new H2RollbackDao(jdbc);
        execLockDao = new H2ExecLockDao(jdbc);
        auditDao = new H2AuditDao(jdbc);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll
    static void closeDataSource() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void cleanTables() {
        if (ds != null) {
            H2TestSupport.cleanAllTables(jdbc);
        }
    }

    /** DDL 为 TIMESTAMP(3) 毫秒精度：截断到毫秒，避免纳秒读写往返误差。 */
    private static LocalDateTime nowMillis() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------
    // TicketDao（对照 InMemoryTicketDaoTest）
    // ------------------------------------------------------------------

    private long insertDraftTicket(long creatorId, LocalDateTime createdAt) {
        Ticket ticket = new Ticket();
        ticket.setTitle("t-" + creatorId);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceIds(new ArrayList<>(List.of(1L, 2L)));
        ticket.setEnvironment("prod");
        ticket.setRiskLevel("HIGH");
        ticket.setStatus("draft");
        ticket.setTraceId("trace-" + creatorId);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return ticketDao.insertTicket(ticket);
    }

    @Test
    void Ticket_insert与findById_JSON列双向转换与字段回读一致() {
        LocalDateTime now = nowMillis();
        Ticket ticket = new Ticket();
        ticket.setTitle("发布工单");
        ticket.setCreatorId(100L);
        ticket.setInstanceIds(List.of(1L, 2L, 3L));
        ticket.setEnvironment("prod");
        ticket.setRiskLevel("CRITICAL");
        ticket.setStatus("draft");
        ticket.setRollbackSummary("{\"rollbackable\":[1],\"unrollbackable\":[]}");
        ticket.setScheduledAt(now.plusHours(2));
        ticket.setTraceId("trace-1");
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        long id = ticketDao.insertTicket(ticket);

        Ticket found = ticketDao.findById(id);
        assertNotNull(found);
        assertEquals("发布工单", found.getTitle());
        assertEquals(100L, found.getCreatorId());
        assertEquals(List.of(1L, 2L, 3L), found.getInstanceIds(), "instance_ids JSON 列序列化/反序列化");
        assertEquals("prod", found.getEnvironment());
        assertEquals("CRITICAL", found.getRiskLevel());
        assertEquals("draft", found.getStatus());
        // H2 JSON 类型不规范化文本格式（区别于 PG jsonb），字节级透传
        assertEquals("{\"rollbackable\":[1],\"unrollbackable\":[]}", found.getRollbackSummary(),
                "rollback_summary JSON 字符串字节级透传（H2 JSON 不规范化）");
        assertEquals(now.plusHours(2), found.getScheduledAt());
        assertEquals("trace-1", found.getTraceId());
        assertEquals(now, found.getCreatedAt());
    }

    @Test
    void Ticket_insert时rollbackSummary为null_回读null() {
        LocalDateTime now = nowMillis();
        Ticket ticket = new Ticket();
        ticket.setTitle("t");
        ticket.setCreatorId(100L);
        ticket.setInstanceIds(List.of(1L));
        ticket.setEnvironment("staging");
        ticket.setRiskLevel("LOW");
        ticket.setStatus("draft");
        ticket.setTraceId("trace-1");
        ticket.setCreatedAt(now);
        long id = ticketDao.insertTicket(ticket);

        Ticket found = ticketDao.findById(id);
        assertNull(found.getRollbackSummary(), "rollback_summary 可空");
        assertNull(found.getScheduledAt());
    }

    @Test
    void Ticket_findById_不存在返回null() {
        assertNull(ticketDao.findById(999L));
    }

    @Test
    void Ticket_updateStatus乐观并发_from匹配返回1并刷新updatedAt_from不匹配返回0_不存在返回0() {
        LocalDateTime now = nowMillis();
        long id = insertDraftTicket(100L, now);
        LocalDateTime changedAt = now.plusMinutes(5);

        assertEquals(1, ticketDao.updateStatus(id, "draft", "submitted", changedAt));
        Ticket found = ticketDao.findById(id);
        assertEquals("submitted", found.getStatus());
        assertEquals(changedAt, found.getUpdatedAt(), "updated_at 置为 changedAt（approval_ttl 基准）");

        // 状态已被并发推进，以 draft 为条件再更新必然失败（乐观并发）
        assertEquals(0, ticketDao.updateStatus(id, "draft", "auditing", now));
        assertEquals("submitted", ticketDao.findById(id).getStatus());

        // 工单不存在
        assertEquals(0, ticketDao.updateStatus(999L, "draft", "submitted", now));
    }

    @Test
    void Ticket_updateStatus并发竞争_同一起点只有一个调用方成功() throws Exception {
        long id = insertDraftTicket(100L, nowMillis());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    success.addAndGet(ticketDao.updateStatus(id, "draft", "submitted", nowMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, success.get(), "并发竞争下同一 fromStatus 只能有一个影响行数 1");
        assertEquals("submitted", ticketDao.findById(id).getStatus());
    }

    @Test
    void Ticket_listByCreator_按创建时间倒序且只含该创建人() {
        LocalDateTime base = nowMillis();
        insertDraftTicket(100L, base.minusHours(3));
        insertDraftTicket(200L, base.minusHours(2));
        insertDraftTicket(100L, base.minusHours(1));

        List<Ticket> mine = ticketDao.listByCreator(100L);
        assertEquals(2, mine.size());
        assertEquals(1, ticketDao.listByCreator(200L).size());
        // 最新在前（created_at DESC, id DESC）
        assertEquals(base.minusHours(1), mine.get(0).getCreatedAt());
        assertEquals(base.minusHours(3), mine.get(1).getCreatedAt());
    }

    @Test
    void Ticket_listByStatus_仅返回匹配状态() {
        long id = insertDraftTicket(100L, nowMillis());
        ticketDao.updateStatus(id, "draft", "submitted", nowMillis());
        insertDraftTicket(200L, nowMillis());

        assertEquals(1, ticketDao.listByStatus("submitted").size());
        assertEquals(1, ticketDao.listByStatus("draft").size());
        assertEquals(0, ticketDao.listByStatus("closed").size());
    }

    // ------------------------------------------------------------------
    // ApprovalDao（对照 InMemoryApprovalDaoTest）
    // ------------------------------------------------------------------

    private long insertApproval(long ticketId, int nodeOrder, long approverId, String mode,
                                String result, LocalDateTime createdAt) {
        Approval approval = new Approval();
        approval.setTicketId(ticketId);
        approval.setNodeId(0L);
        approval.setNodeOrder(nodeOrder);
        approval.setApproverId(approverId);
        approval.setMode(mode);
        approval.setResult(result);
        approval.setCreatedAt(createdAt);
        return approvalDao.insert(approval);
    }

    @Test
    void Approval_insert与listByTicket_按nodeOrder再按id稳定排序() {
        LocalDateTime now = nowMillis();
        long t1 = 1L;
        // 先插 node2 再插 node1（含同 nodeOrder 多行 = 会签组），验证排序稳定
        insertApproval(t1, 2, 0L, "AND", "PENDING", now);
        long firstOfNode1 = insertApproval(t1, 1, 10L, "AND", "PENDING", now);
        long secondOfNode1 = insertApproval(t1, 1, 20L, "AND", "PENDING", now);
        insertApproval(2L, 1, 0L, "OR", "PENDING", now);

        List<Approval> rows = approvalDao.listByTicket(t1);
        assertEquals(3, rows.size());
        assertEquals(firstOfNode1, rows.get(0).getId());
        assertEquals(secondOfNode1, rows.get(1).getId());
        assertEquals(2, rows.get(2).getNodeOrder());
        assertTrue(rows.stream().allMatch(r -> r.getTicketId() == t1));
    }

    @Test
    void Approval_updateResult条件更新_PENDING到APPROVED成功并记录决策人() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, 0L, "AND", "PENDING", now);
        LocalDateTime decidedAt = now.plusMinutes(10);

        assertEquals(1, approvalDao.updateResult(id, 30L, "PENDING", "APPROVED", "同意", decidedAt));
        Approval found = approvalDao.listByTicket(1L).get(0);
        assertEquals("APPROVED", found.getResult());
        assertEquals(30L, found.getApproverId(), "ROLE 占位记录被决策人认领");
        assertEquals("同意", found.getComment());
        assertEquals(decidedAt, found.getDecidedAt());
    }

    @Test
    void Approval_updateResult_已决策记录返回0且不被覆盖() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, 10L, "AND", "PENDING", now);
        assertEquals(1, approvalDao.updateResult(id, 10L, "PENDING", "APPROVED", "ok", now));

        // 已是 APPROVED：以 PENDING 为条件的更新（无论目标是什么）都失败
        assertEquals(0, approvalDao.updateResult(id, 10L, "PENDING", "REJECTED", "改主意", now));
        Approval found = approvalDao.listByTicket(1L).get(0);
        assertEquals("APPROVED", found.getResult());
        assertEquals("ok", found.getComment(), "comment 不被覆盖");
    }

    @Test
    void Approval_updateResult_支持转交TRANSFERRED_记录不存在返回0() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, 10L, "OR", "PENDING", now);
        assertEquals(1, approvalDao.updateResult(id, 10L, "PENDING", "TRANSFERRED", "转交 u20", now));
        assertEquals("TRANSFERRED", approvalDao.listByTicket(1L).get(0).getResult());

        assertEquals(0, approvalDao.updateResult(999L, 10L, "PENDING", "APPROVED", null, now));
    }

    @Test
    void Approval_listPendingCreatedBefore_只返回超时的PENDING记录() {
        LocalDateTime now = nowMillis();
        long overduePending = insertApproval(1L, 1, 0L, "AND", "PENDING", now.minusHours(2));
        insertApproval(1L, 1, 20L, "AND", "PENDING", now.minusMinutes(5)); // 未超时
        insertApproval(1L, 1, 30L, "AND", "APPROVED", now.minusHours(3)); // 超时但已决策
        insertApproval(2L, 1, 0L, "OR", "REJECTED", now.minusHours(3));    // 超时但已决策

        List<Approval> overdue = approvalDao.listPendingCreatedBefore(now.minusHours(1));
        assertEquals(1, overdue.size());
        assertEquals(overduePending, overdue.get(0).getId());
        assertNotNull(overdue.get(0).getCreatedAt());
    }

    @Test
    void Approval_listPending_全部PENDING按创建时间排序() {
        LocalDateTime now = nowMillis();
        long older = insertApproval(1L, 1, 10L, "AND", "PENDING", now.minusHours(2));
        long newer = insertApproval(2L, 1, 20L, "OR", "PENDING", now.minusMinutes(5));
        insertApproval(3L, 1, 30L, "AND", "APPROVED", now.minusHours(3)); // 非 PENDING 不含

        List<Approval> pending = approvalDao.listPending();
        assertEquals(2, pending.size());
        assertEquals(older, pending.get(0).getId(), "created_at, id 升序");
        assertEquals(newer, pending.get(1).getId());
    }

    // ------------------------------------------------------------------
    // RollbackDao（对照 InMemoryRollbackDaoTest）
    // ------------------------------------------------------------------

    private long insertRollback(long unitId) {
        RollbackSql rollbackSql = new RollbackSql();
        rollbackSql.setUnitId(unitId);
        rollbackSql.setStmtCount(2);
        rollbackSql.setTotalBytes(64L);
        rollbackSql.setGenerator("MIRROR");
        rollbackSql.setStatus("generated");
        rollbackSql.setCoverage("FULL");
        rollbackSql.setCreatedAt(nowMillis());
        assertEquals(1, rollbackDao.insertRollbackSql(rollbackSql));
        return rollbackSql.getId();
    }

    @Test
    void Rollback_insertRollbackSql_unitId唯一_重复插入返回0_findByUnitId() {
        insertRollback(100L);
        RollbackSql duplicate = new RollbackSql();
        duplicate.setUnitId(100L);
        duplicate.setGenerator("MIRROR");
        duplicate.setStatus("generated");
        duplicate.setCoverage("FULL");
        assertEquals(0, rollbackDao.insertRollbackSql(duplicate), "uk_unit 冲突应返回 0");

        RollbackSql found = rollbackDao.findByUnitId(100L);
        assertNotNull(found);
        assertEquals(100L, found.getUnitId());
        assertEquals("generated", found.getStatus());
        assertEquals("FULL", found.getCoverage());
        assertEquals("MIRROR", found.getGenerator());
        assertEquals(2, found.getStmtCount());
        assertEquals(64L, found.getTotalBytes());
        assertNull(rollbackDao.findByUnitId(999L));
    }

    @Test
    void Rollback_insertRollbackStatement_rollbackId加stmtOrder唯一() {
        long rollbackId = insertRollback(100L);

        RollbackStatement first = makeStatement(rollbackId, 1);
        assertEquals(1, rollbackDao.insertRollbackStatement(first));

        RollbackStatement duplicateOrder = makeStatement(rollbackId, 1);
        assertEquals(0, rollbackDao.insertRollbackStatement(duplicateOrder), "uk_rb_order 冲突应返回 0");

        RollbackStatement second = makeStatement(rollbackId, 2);
        assertEquals(1, rollbackDao.insertRollbackStatement(second));

        // 回读校验 BYTEA 字段往返
        RollbackStatement read = rollbackDao.listStatements(rollbackId, 0, 10).get(0);
        assertArrayEquals(new byte[] {1, 2, 3}, read.getReverseSql());
        assertArrayEquals(new byte[] {4, 5, 6}, read.getValueParams());
    }

    @Test
    void Rollback_listStatements_按stmtOrder升序_分页正确() {
        long rollbackId = insertRollback(100L);
        // 乱序插入 3 条
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 3));
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 1));
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 2));

        List<RollbackStatement> all = rollbackDao.listStatements(rollbackId, 0, 10);
        assertEquals(3, all.size());
        assertEquals(1, all.get(0).getStmtOrder());
        assertEquals(2, all.get(1).getStmtOrder());
        assertEquals(3, all.get(2).getStmtOrder());

        // 分批流式读取：offset + limit
        List<RollbackStatement> batch = rollbackDao.listStatements(rollbackId, 1, 1);
        assertEquals(1, batch.size());
        assertEquals(2, batch.get(0).getStmtOrder());

        // 其他 rollback 的语句不混入
        long otherId = insertRollback(200L);
        rollbackDao.insertRollbackStatement(makeStatement(otherId, 1));
        assertEquals(3, rollbackDao.listStatements(rollbackId, 0, 10).size());
    }

    @Test
    void Rollback_markStatementApplied_条件更新幂等() {
        long rollbackId = insertRollback(100L);
        RollbackStatement stmt = makeStatement(rollbackId, 1);
        rollbackDao.insertRollbackStatement(stmt);
        long statementId = stmt.getId();

        LocalDateTime appliedAt = nowMillis();
        assertEquals(1, rollbackDao.markStatementApplied(statementId, appliedAt));
        assertEquals(appliedAt, rollbackDao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt());

        // 已 applied 的重复回放返回 0（applied_at IS NULL 条件不满足）
        assertEquals(0, rollbackDao.markStatementApplied(statementId, appliedAt.plusSeconds(1)));
        assertEquals(appliedAt, rollbackDao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt(),
                "重复回放不得覆盖首次回放时间戳");

        // 不存在的语句返回 0
        assertEquals(0, rollbackDao.markStatementApplied(99999L, appliedAt));
    }

    @Test
    void Rollback_updateStatus_更新主记录状态与原因() {
        long rollbackId = insertRollback(100L);
        assertEquals(1, rollbackDao.updateStatus(rollbackId, "not_available", "R_ROLLBACK_UNAVAILABLE"));
        RollbackSql rollbackSql = rollbackDao.findByUnitId(100L);
        assertEquals("not_available", rollbackSql.getStatus());
        assertEquals("R_ROLLBACK_UNAVAILABLE", rollbackSql.getUnavailableReason(), "列名 unavailable_reason");

        assertEquals(0, rollbackDao.updateStatus(99999L, "verified", null));
    }

    private RollbackStatement makeStatement(long rollbackId, int stmtOrder) {
        RollbackStatement statement = new RollbackStatement();
        statement.setRollbackId(rollbackId);
        statement.setStmtOrder(stmtOrder);
        statement.setReverseSql(new byte[] {1, 2, 3});
        statement.setValueParams(new byte[] {4, 5, 6});
        return statement;
    }

    // ------------------------------------------------------------------
    // ExecLockDao（对照 InMemoryExecLockDaoTest；过期锁由 reaper 清理后重抢）
    // ------------------------------------------------------------------

    private ExecLock makeLock(String lockKey, long unitId, String workerId,
                              LocalDateTime acquiredAt, LocalDateTime expiresAt) {
        ExecLock lock = new ExecLock();
        lock.setLockKey(lockKey);
        lock.setUnitId(unitId);
        lock.setWorkerId(workerId);
        lock.setAcquiredAt(acquiredAt);
        lock.setExpiresAt(expiresAt);
        return lock;
    }

    @Test
    void ExecLock_tryAcquire_首次true_二次false_同owner也不行() {
        LocalDateTime now = nowMillis();
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // 同 key 第二个 unit（不同 owner）：主键冲突，未过期不可剥夺
        assertFalse(execLockDao.tryAcquire(makeLock("inst:1", 11L, "worker-b", now, now.plusSeconds(30))));
        // 同 owner 重入也算剥夺，返回 false
        assertFalse(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.plusSeconds(1), now.plusSeconds(31))));
    }

    @Test
    void ExecLock_release_只删lockKey与unitId都匹配的锁() {
        LocalDateTime now = nowMillis();
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // unitId 不匹配：别人的锁删不掉
        assertFalse(execLockDao.release("inst:1", 999L));
        // lockKey 不匹配
        assertFalse(execLockDao.release("inst:2", 10L));

        // 匹配：删除成功；已删除再删返回 false
        assertTrue(execLockDao.release("inst:1", 10L));
        assertFalse(execLockDao.release("inst:1", 10L));

        // 删除后可重新抢到
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 12L, "worker-c", now, now.plusSeconds(30))));
    }

    @Test
    void ExecLock_cleanupExpired_只清过期的并返回条数_过期锁清理后可重抢() {
        LocalDateTime now = nowMillis();
        execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.minusSeconds(60), now.minusSeconds(30)));
        execLockDao.tryAcquire(makeLock("inst:2", 20L, "worker-b", now.minusSeconds(60), now.minusSeconds(30)));
        execLockDao.tryAcquire(makeLock("inst:3", 30L, "worker-c", now, now.plusSeconds(30)));

        assertEquals(2, execLockDao.cleanupExpired(now));

        // 未过期的还在：unit 30 仍持有 inst:3
        assertFalse(execLockDao.tryAcquire(makeLock("inst:3", 31L, "worker-d", now, now.plusSeconds(30))));
        // 过期的已被清走：inst:1 可重新抢到（JDBC 版过期锁经 reaper 清理后恢复，见类 javadoc 语义差异说明）
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 11L, "worker-e", now, now.plusSeconds(30))));
    }

    // ------------------------------------------------------------------
    // AuditDao（对照 InMemoryAuditDaoTest；行锁语义用 TransactionTemplate 验证）
    // ------------------------------------------------------------------

    private AuditLog makeLog(long seq, String prevHash, String hash) {
        AuditLog log = new AuditLog();
        log.setSeq(seq);
        log.setActorId(1L);
        log.setActorName("alice");
        log.setActorRole("DBA");
        log.setAction("EXECUTE_UNIT");
        log.setTargetType("unit");
        log.setTargetId("10");
        log.setInstanceId(1L);
        log.setDetail(new byte[] {1, 2, 3});
        log.setResult("SUCCESS");
        log.setTraceId("trace-1");
        log.setPrevHash(prevHash);
        log.setHash(hash);
        return log;
    }

    @Test
    void Audit_创世链头_lastSeq为0_lastHash是64个0() {
        // 无锁读即可校验创世状态（cleanAllTables 已重放创世块）
        ChainHead head = auditDao.peekChainHead();
        assertEquals(0L, head.getLastSeq());
        assertEquals("0".repeat(64), head.getLastHash());
    }

    @Test
    void Audit_append流程_事务内lock读链头_insert_updateChainHead_seq递增无空洞() {
        String genesis = "0".repeat(64);

        // 第一条：prev_hash = 创世 hash（FOR UPDATE 行锁须在事务内）
        txTemplate.executeWithoutResult(status -> {
            ChainHead head1 = auditDao.lockChainHead();
            assertEquals(0L, head1.getLastSeq());
            assertEquals(genesis, head1.getLastHash());

            assertEquals(1, auditDao.insert(makeLog(1L, head1.getLastHash(), "hash-1")));
            assertEquals(1, auditDao.updateChainHead(1L, "hash-1"));
        });

        // 第二条：链头已推进
        txTemplate.executeWithoutResult(status -> {
            ChainHead head2 = auditDao.lockChainHead();
            assertEquals(1L, head2.getLastSeq());
            assertEquals("hash-1", head2.getLastHash());

            assertEquals(1, auditDao.insert(makeLog(2L, head2.getLastHash(), "hash-2")));
            assertEquals(1, auditDao.updateChainHead(2L, "hash-2"));
        });

        ChainHead current = auditDao.peekChainHead();
        assertEquals(2L, current.getLastSeq());
        assertEquals("hash-2", current.getLastHash());

        // seq 递增无空洞 + prev_hash 链衔接 + detail BYTEA 往返
        List<AuditLog> logs = auditDao.listBySeqRange(1L, 2L);
        assertEquals(2, logs.size());
        assertEquals(1L, logs.get(0).getSeq());
        assertEquals(2L, logs.get(1).getSeq());
        assertEquals(genesis, logs.get(0).getPrevHash());
        assertEquals("hash-1", logs.get(1).getPrevHash());
        assertArrayEquals(new byte[] {1, 2, 3}, logs.get(0).getDetail());
        assertTrue(logs.get(0).getId() != logs.get(1).getId(), "物理主键自增且不覆盖");
    }

    @Test
    void Audit_listBySeqRange_区间边界与排序() {
        // 直接插入（insert 本身不依赖锁，内存版测试同款造数方式）
        for (long seq = 1; seq <= 5; seq++) {
            auditDao.insert(makeLog(seq, "prev-" + seq, "hash-" + seq));
        }
        auditDao.insert(makeLog(7L, "prev-7", "hash-7"));
        auditDao.insert(makeLog(6L, "prev-6", "hash-6"));

        List<AuditLog> range = auditDao.listBySeqRange(2L, 6L);
        assertEquals(5, range.size(), "乱序插入后按 seq 升序返回");
        for (int i = 0; i < range.size(); i++) {
            assertEquals(2L + i, range.get(i).getSeq());
        }

        assertEquals(0, auditDao.listBySeqRange(100L, 200L).size());
    }

    @Test
    void Audit_并发append_FOR_UPDATE行锁串行_seq连续无空洞() throws Exception {
        int threads = 6;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 单条 append 流程：lock → 算 seq/hash → insert → updateChainHead，全程一个事务
                    txTemplate.executeWithoutResult(status -> {
                        ChainHead head = auditDao.lockChainHead();
                        long seq = head.getLastSeq() + 1;
                        String hash = "hash-" + seq;
                        assertEquals(1, auditDao.insert(makeLog(seq, head.getLastHash(), hash)));
                        assertEquals(1, auditDao.updateChainHead(seq, hash));
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发 append 应全部完成");
        pool.shutdownNow();

        // 行锁串行 → seq 连续无空洞，hash 链两两衔接
        assertEquals((long) threads, auditDao.peekChainHead().getLastSeq());
        List<AuditLog> logs = auditDao.listBySeqRange(1L, threads);
        assertEquals(threads, logs.size());
        assertEquals("0".repeat(64), logs.get(0).getPrevHash());
        for (int i = 0; i < logs.size(); i++) {
            assertEquals(1L + i, logs.get(i).getSeq(), "seq 连续无空洞");
            if (i > 0) {
                assertEquals(logs.get(i - 1).getHash(), logs.get(i).getPrevHash(), "prev_hash 链衔接");
            }
        }
    }
}
