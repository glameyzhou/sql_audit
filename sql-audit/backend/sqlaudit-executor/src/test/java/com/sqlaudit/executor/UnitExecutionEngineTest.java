package com.sqlaudit.executor;

import com.sqlaudit.executor.engine.UnitExecutionEngine;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.storage.impl.memory.InMemoryExecLockDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.model.ExecLock;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行单元引擎协议测试（§3.3 抢占与执行协议 + §10.2 故障注入的单测投影）。
 *
 * <p>用的 fake：InMemoryTicketUnitDao（协议语义已由 storage 侧测试验证）+
 * 可编排的 gateway/persister。</p>
 */
class UnitExecutionEngineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 18, 0, 0);
    private static final String WORKER = "worker-1";

    private final InMemoryTicketUnitDao unitDao = new InMemoryTicketUnitDao();
    private final InMemoryExecLockDao lockDao = new InMemoryExecLockDao();
    /** 生产库执行次数（断言「不重复执行」用） */
    private final AtomicInteger gatewayCalls = new AtomicInteger();
    private ProductionGateway.ExecutionResult nextResult =
            ProductionGateway.ExecutionResult.success(List.of(Map.of("id", 1, "a", 10)), 1, null);

    private final UnitExecutionEngine engine = new UnitExecutionEngine(
            unitDao, lockDao, null,
            unit -> {
                gatewayCalls.incrementAndGet();
                return nextResult;
            },
            (unit, result) -> true);

    /** 插入 pending unit，返回落库后的真实 id。 */
    private long newPendingUnit() {
        TicketUnit u = new TicketUnit();
        u.setTicketId(1L);
        u.setInstanceId(7L);
        u.setUnitOrder(1);
        u.setStatement("UPDATE t SET a=1 WHERE id=1".getBytes());
        u.setStatementHash("hash");
        u.setStatementType("UPDATE");
        u.setStatus("pending");
        u.setIntentWritten(false);
        return unitDao.insert(u);
    }

    // ---------------- 正常路径 ----------------

    @Test
    void fullSuccessPath() {
        long id = newPendingUnit();
        nextResult = ProductionGateway.ExecutionResult.success(
                List.of(Map.of("id", 1, "a", 10)), 1, null);

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.SUCCESS, r.status());
        assertEquals(1, r.affectedRows());
        assertEquals("success", unitDao.findById(id).getStatus());
        assertEquals(Boolean.TRUE, unitDao.findById(id).getIntentWritten());
        assertEquals(1, gatewayCalls.get());
    }

    // ---------------- 抢占协议 ----------------

    @Test
    void claimHardCondition_intentWritten_neverClaimedAgain() {
        // §10.2 双执行场景：unit 已 success（intent_written=1）→ 永远不进抢占池
        long id = newPendingUnit();
        engine.execute(unitDao.findById(id), WORKER, NOW); // 第一次成功

        UnitExecutionEngine.EngineResult again =
                engine.execute(unitDao.findById(id), WORKER, NOW.plusSeconds(60));

        assertEquals(UnitExecutionEngine.EngineResult.Status.NOT_CLAIMED, again.status());
        assertEquals(1, gatewayCalls.get(), "生产库绝不执行第二次（双执行故障注入）");
    }

    @Test
    void claimConflict_onlyOneWorkerWins() {
        long id = newPendingUnit();
        // 另一个 worker 先抢占（租约未过期）
        assertTrue(unitDao.claimUnit(id, "worker-2", NOW.plusSeconds(30), NOW) == 1);

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.NOT_CLAIMED, r.status());
        assertEquals(0, gatewayCalls.get());
        assertEquals("leased", unitDao.findById(id).getStatus());
    }

    // ---------------- 执行锁 ----------------

    @Test
    void execLockBusy_releaseLeaseBackToPending() {
        // §10.2 并发工单场景：另一 unit 持有实例锁 → 本 unit 退回 pending 等下一轮
        long holderId = newPendingUnit();
        long id = newPendingUnit();

        ExecLock lock = new ExecLock();
        lock.setLockKey("inst:7");
        lock.setUnitId(holderId);
        lock.setWorkerId("worker-2");
        lock.setAcquiredAt(NOW);
        lock.setExpiresAt(NOW.plusSeconds(30));
        assertTrue(lockDao.tryAcquire(lock));

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.LOCK_BUSY, r.status());
        assertEquals("pending", unitDao.findById(id).getStatus(), "释放租约退回 pending");
        assertEquals(0, gatewayCalls.get());
    }

    // ---------------- 失败路径分流（§3.3 协议表） ----------------

    @Test
    void reconcileFailure_marksNeedsConfirm_neverAutoRetry() {
        // §5.3 硬门禁：affected != preimage → 事务回滚 + needs_confirm + 禁止自动重试
        long id = newPendingUnit();
        nextResult = ProductionGateway.ExecutionResult.reconcileFailure(3, 5);

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.NEEDS_CONFIRM, r.status());
        assertEquals("needs_confirm", unitDao.findById(id).getStatus());

        // needs_confirm 后重试：claimUnit 硬条件拦住（DAO 语义），绝不自动重跑
        UnitExecutionEngine.EngineResult retry =
                engine.execute(unitDao.findById(id), WORKER, NOW.plusSeconds(60));
        assertEquals(UnitExecutionEngine.EngineResult.Status.NOT_CLAIMED, retry.status());
        assertEquals(1, gatewayCalls.get());
    }

    @Test
    void executionError_marksFailed_safeToRetry() {
        long id = newPendingUnit();
        nextResult = ProductionGateway.ExecutionResult.executionError("syntax error near ')'");

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.FAILED, r.status());
        assertEquals("failed", unitDao.findById(id).getStatus());
        assertTrue(unitDao.findById(id).getErrorMessage().contains("syntax error"));
    }

    @Test
    void rollbackPersistFailure_needsConfirm_keepsRollbackPriority() {
        // 生产库已变更但反向 SQL 落库失败 → needs_confirm 人工兜底（保回滚能力优先）
        long id = newPendingUnit();
        final UnitExecutionEngine engine = new UnitExecutionEngine(
                unitDao, lockDao, null,
                u -> {
                    gatewayCalls.incrementAndGet();
                    return ProductionGateway.ExecutionResult.success(
                            List.of(Map.of("id", 1, "a", 10)), 1, null);
                },
                (u, result) -> false); // 落库失败

        UnitExecutionEngine.EngineResult r = engine.execute(unitDao.findById(id), WORKER, NOW);

        assertEquals(UnitExecutionEngine.EngineResult.Status.NEEDS_CONFIRM, r.status());
        assertEquals("needs_confirm", unitDao.findById(id).getStatus());
    }

    @Test
    void lockReleasedEvenOnFailure() {
        // finally 语义：执行失败也必须释放执行锁（否则锁泄漏，同实例永久阻塞）
        long id = newPendingUnit();
        nextResult = ProductionGateway.ExecutionResult.executionError("boom");

        engine.execute(unitDao.findById(id), WORKER, NOW);

        // 同实例新 unit 可以立即拿锁
        long nextId = newPendingUnit();
        UnitExecutionEngine.EngineResult r2 =
                engine.execute(unitDao.findById(nextId), WORKER, NOW.plusSeconds(1));
        assertEquals(UnitExecutionEngine.EngineResult.Status.FAILED, r2.status());
    }
}
