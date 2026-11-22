package com.sqlaudit.server;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.audit.NoopDetailCipher;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.server.service.RollbackExecutor;
import com.sqlaudit.storage.impl.memory.InMemoryAuditDao;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚执行单测（内存 fake DAO + fake {@link ProductionGateway}）。
 *
 * <p>覆盖：正常回滚 applied / 过期拒绝 / 中途失败 partial / 审计落库。</p>
 */
class RollbackExecuteTest {

    private InMemoryRollbackDao rollbackDao;
    private InMemoryTicketUnitDao ticketUnitDao;
    private InMemoryAuditDao auditDao;
    private RollbackExecutor executor;

    @BeforeEach
    void setUp() {
        rollbackDao = new InMemoryRollbackDao();
        ticketUnitDao = new InMemoryTicketUnitDao();
        auditDao = new InMemoryAuditDao();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        AuditWriter writer = new AuditWriter(auditDao, new NoopDetailCipher(), zone);
        // 默认网关：每条成功、影响 1 行
        executor = new RollbackExecutor(rollbackDao, ticketUnitDao, successGateway(), writer, zone);
    }

    @Test
    void executeRollback_allStatementsApplied() {
        long unitId = newUnit(7L);
        long rollbackId = newRollback(unitId, "verified", LocalDateTime.now().plusHours(1), 2);
        newStatement(rollbackId, 0, "UPDATE t SET a=? WHERE id=?", "[10,1]");
        newStatement(rollbackId, 1, "DELETE FROM t WHERE id=?", "[2]");

        RollbackExecutor.Result result = executor.executeRollback(rollbackId, 100L);

        assertEquals("applied", result.status());
        assertEquals(2, result.applied());
        assertEquals("applied", rollbackDao.findById(rollbackId).getStatus());
        assertEquals(2, countApplied(rollbackId));
        // 反向语句按序执行，字面量已拼接
        List<String> executed = lastExecutedSql;
        assertEquals(2, executed.size());
        assertEquals("UPDATE t SET a=10 WHERE id=1", executed.get(0));
        assertEquals("DELETE FROM t WHERE id=2", executed.get(1));
        // 审计：ROLLBACK_EXECUTE + 2 条 ROLLBACK_STATEMENT_APPLIED
        assertAuditContains("ROLLBACK_EXECUTE", "SUCCESS");
        assertEquals(2, countAuditAction("ROLLBACK_STATEMENT_APPLIED"));
    }

    @Test
    void executeRollback_expired_rejected() {
        long unitId = newUnit(7L);
        long rollbackId = newRollback(unitId, "verified", LocalDateTime.now().minusSeconds(1), 1);
        newStatement(rollbackId, 0, "UPDATE t SET a=? WHERE id=?", "[10,1]");

        SqlauditException e = assertThrows(SqlauditException.class,
                () -> executor.executeRollback(rollbackId, 100L));

        assertEquals("ROLLBACK_EXPIRED", e.getCode());
        assertEquals("expired", rollbackDao.findById(rollbackId).getStatus());
        // 过期拒绝不执行任何语句
        assertTrue(lastExecutedSql.isEmpty());
    }

    @Test
    void executeRollback_midwayFailure_partial() {
        long unitId = newUnit(7L);
        long rollbackId = newRollback(unitId, "verified", LocalDateTime.now().plusHours(1), 2);
        newStatement(rollbackId, 0, "UPDATE t SET a=? WHERE id=?", "[10,1]");
        newStatement(rollbackId, 1, "UPDATE t SET a=? WHERE id=?", "[20,2]");

        AtomicInteger calls = new AtomicInteger();
        executor = new RollbackExecutor(rollbackDao, ticketUnitDao, req -> {
            if (calls.incrementAndGet() == 2) {
                return ProductionGateway.ExecutionResult.executionError("连接中断");
            }
            return ProductionGateway.ExecutionResult.success(null, 1, null);
        }, new AuditWriter(auditDao, new NoopDetailCipher(), ZoneId.of("Asia/Shanghai")),
                ZoneId.of("Asia/Shanghai"));

        RollbackExecutor.Result result = executor.executeRollback(rollbackId, 100L);

        assertEquals("partial", result.status());
        assertEquals(1, result.applied());
        // 已应用的第一条保留 applied 标记，第二条未应用
        assertEquals(1, countApplied(rollbackId));
        assertAuditContains("ROLLBACK_EXECUTE", "PARTIAL");
    }

    @Test
    void executeRollback_zeroAffected_fails() {
        long unitId = newUnit(7L);
        long rollbackId = newRollback(unitId, "verified", LocalDateTime.now().plusHours(1), 1);
        newStatement(rollbackId, 0, "UPDATE t SET a=? WHERE id=?", "[10,1]");

        executor = new RollbackExecutor(rollbackDao, ticketUnitDao,
                req -> ProductionGateway.ExecutionResult.success(null, 0, null),
                new AuditWriter(auditDao, new NoopDetailCipher(), ZoneId.of("Asia/Shanghai")),
                ZoneId.of("Asia/Shanghai"));

        RollbackExecutor.Result result = executor.executeRollback(rollbackId, 100L);

        assertEquals("failed", result.status());
        assertEquals(0, result.applied());
        assertEquals(0, countApplied(rollbackId));
    }

    // ---------------- 构造辅助 ----------------

    /** 默认全成功网关，并记录执行过的 SQL。 */
    private final List<String> lastExecutedSql = new ArrayList<>();

    private ProductionGateway successGateway() {
        return req -> {
            lastExecutedSql.add(req.statementSql());
            return ProductionGateway.ExecutionResult.success(null, 1, null);
        };
    }

    private long newUnit(long instanceId) {
        TicketUnit unit = new TicketUnit();
        unit.setInstanceId(instanceId);
        return ticketUnitDao.insert(unit);
    }

    private long newRollback(long unitId, String status, LocalDateTime expiresAt, int stmtCount) {
        RollbackSql r = new RollbackSql();
        r.setUnitId(unitId);
        r.setGenerator("MIRROR");
        r.setStatus(status);
        r.setCoverage("full");
        r.setStmtCount(stmtCount);
        r.setExpiresAt(expiresAt);
        rollbackDao.insertRollbackSql(r);
        assertNotNull(r.getId());
        return r.getId();
    }

    private void newStatement(long rollbackId, int order, String sql, String paramsJson) {
        RollbackStatement s = new RollbackStatement();
        s.setRollbackId(rollbackId);
        s.setStmtOrder(order);
        s.setReverseSql(sql.getBytes(StandardCharsets.UTF_8));
        s.setValueParams(paramsJson.getBytes(StandardCharsets.UTF_8));
        rollbackDao.insertRollbackStatement(s);
    }

    private int countApplied(long rollbackId) {
        int count = 0;
        for (RollbackStatement s : rollbackDao.listStatements(rollbackId, 0, -1)) {
            if (s.getAppliedAt() != null) {
                count++;
            }
        }
        return count;
    }

    private long countAuditAction(String action) {
        return auditDao.listBySeqRange(1, 10_000).stream()
                .filter(l -> action.equals(l.getAction()))
                .count();
    }

    private void assertAuditContains(String action, String result) {
        List<AuditLog> logs = auditDao.listBySeqRange(1, 10_000);
        assertTrue(logs.stream().anyMatch(l -> action.equals(l.getAction())
                && result.equals(l.getResult())),
                "审计链应含 action=" + action + " result=" + result);
    }
}
