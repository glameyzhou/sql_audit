package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryTicketUnitDao} 单测：覆盖 §3.3 抢占与幂等协议的核心语义。
 */
class InMemoryTicketUnitDaoTest {

    private final InMemoryTicketUnitDao dao = new InMemoryTicketUnitDao();

    private long insertUnit(UnitStatus status, Boolean intentWritten, LocalDateTime leaseUntil) {
        TicketUnit unit = new TicketUnit();
        unit.setStatus(status);
        unit.setIntentWritten(intentWritten);
        unit.setLeaseUntil(leaseUntil);
        unit.setExecFingerprint("fp-default");
        unit.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return dao.insert(unit);
    }

    @Test
    void 全生命周期_pending到claim到writeIntent到confirmSuccess() {
        long id = insertUnit(UnitStatus.PENDING, false, null);
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));
        TicketUnit leased = dao.findById(id);
        assertEquals(UnitStatus.LEASED, leased.getStatus());
        assertEquals("worker-1", leased.getLeaseOwner());
        assertNotNull(leased.getLeaseUntil());
        assertNotNull(leased.getHeartbeatAt());

        assertEquals(1, dao.writeIntent(id, now));
        TicketUnit executing = dao.findById(id);
        assertEquals(UnitStatus.EXECUTING, executing.getStatus());
        assertEquals(Boolean.TRUE, executing.getIntentWritten());

        assertEquals(1, dao.confirmSuccess(id, 42L, now.plusSeconds(2)));
        TicketUnit success = dao.findById(id);
        assertEquals(UnitStatus.SUCCESS, success.getStatus());
        assertEquals(42L, success.getAffectedRows());
        assertNotNull(success.getExecutedAt());

        // 幂等确认：二次 confirmSuccess 返回 0，调用方视为成功
        assertEquals(0, dao.confirmSuccess(id, 42L, now.plusSeconds(3)));
    }

    @Test
    void claimUnit硬条件_intent已预写的unit永远抢不到() {
        LocalDateTime now = LocalDateTime.now();

        // pending + intent_written=1：抢不到
        TicketUnit pendingButWritten = new TicketUnit();
        pendingButWritten.setStatus(UnitStatus.PENDING);
        pendingButWritten.setIntentWritten(true);
        long id1 = dao.insert(pendingButWritten);
        assertEquals(0, dao.claimUnit(id1, "worker-1", now.plusSeconds(30), now));

        // 租约已过期的 leased + intent_written=1：同样抢不到（硬条件优先于租约过期）
        TicketUnit leasedExpiredButWritten = new TicketUnit();
        leasedExpiredButWritten.setStatus(UnitStatus.LEASED);
        leasedExpiredButWritten.setIntentWritten(true);
        leasedExpiredButWritten.setLeaseUntil(now.minusSeconds(1));
        long id2 = dao.insert(leasedExpiredButWritten);
        assertEquals(0, dao.claimUnit(id2, "worker-1", now.plusSeconds(30), now));

        assertEquals(UnitStatus.PENDING, dao.findById(id1).getStatus());
        assertEquals(UnitStatus.LEASED, dao.findById(id2).getStatus());
    }

    @Test
    void claimUnit_租约未过期的leased不可抢占_过期可抢占() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertUnit(UnitStatus.LEASED, false, now.plusSeconds(30));
        assertEquals(0, dao.claimUnit(id, "worker-2", now.plusSeconds(30), now));

        long expiredId = insertUnit(UnitStatus.LEASED, false, now.minusSeconds(1));
        assertEquals(1, dao.claimUnit(expiredId, "worker-2", now.plusSeconds(30), now));
        assertEquals(UnitStatus.LEASED, dao.findById(expiredId).getStatus());
        assertEquals("worker-2", dao.findById(expiredId).getLeaseOwner());
    }

    @Test
    void 并发抢占_两线程只有一个claim成功() throws Exception {
        long id = insertUnit(UnitStatus.PENDING, false, null);
        LocalDateTime now = LocalDateTime.now();
        int threads = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        int[] results = new int[threads];

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    results[idx] = dao.claimUnit(id, "worker-" + idx, now.plusSeconds(30), now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(5, TimeUnit.SECONDS), "并发抢占应全部完成");
        pool.shutdownNow();

        int totalClaimed = 0;
        for (int r : results) {
            totalClaimed += r;
        }
        assertEquals(1, totalClaimed, "同一个 unit 只能被一个 worker 抢到");
        assertEquals(UnitStatus.LEASED, dao.findById(id).getStatus());
    }

    @Test
    void reclaimExpiredLease_只回收intent未预写的() {
        LocalDateTime now = LocalDateTime.now();
        // 可回收：leased + 过期 + intent_written=0
        long reclaimable = insertUnit(UnitStatus.LEASED, false, now.minusSeconds(1));
        // 不可回收：intent_written=1（即使租约过期）
        long written = insertUnit(UnitStatus.LEASED, true, now.minusSeconds(1));
        // 不可回收：未过期
        long fresh = insertUnit(UnitStatus.LEASED, false, now.plusSeconds(60));

        assertEquals(1, dao.reclaimExpiredLease(now));

        TicketUnit reclaimed = dao.findById(reclaimable);
        assertEquals(UnitStatus.PENDING, reclaimed.getStatus());
        assertNull(reclaimed.getLeaseOwner());
        assertNull(reclaimed.getLeaseUntil());
        assertNull(reclaimed.getHeartbeatAt());

        assertEquals(UnitStatus.LEASED, dao.findById(written).getStatus(), "intent 已预写的不回收");
        assertEquals(UnitStatus.LEASED, dao.findById(fresh).getStatus(), "租约未过期的不回收");
    }

    @Test
    void executing心跳停滞_走listExecutingWithStaleHeartbeat不进reclaim() {
        LocalDateTime now = LocalDateTime.now();
        // executing + 心跳停滞 + 未确认：reclaim 不碰（intent_written=1 且非 pending/leased）
        long staleId = dao.insert(makeExecuting(now.minusSeconds(300), null));
        // executing + 心跳新鲜：不回收、不告警
        dao.insert(makeExecuting(now.minusSeconds(1), null));
        // executing + 心跳停滞但已确认：不在停滞列表内
        dao.insert(makeExecuting(now.minusSeconds(300), now));

        assertEquals(0, dao.reclaimExpiredLease(now), "executing 状态不走租约回收");
        assertEquals(UnitStatus.EXECUTING, dao.findById(staleId).getStatus());

        // 由 listExecutingWithStaleHeartbeat 分流（转 needs_confirm 由 reaper 决策）
        List<TicketUnit> staleList = dao.listExecutingWithStaleHeartbeat(now.minusSeconds(60));
        assertEquals(1, staleList.size());
        assertEquals(staleId, staleList.get(0).getId());
    }

    private TicketUnit makeExecuting(LocalDateTime heartbeatAt, LocalDateTime executedAt) {
        TicketUnit unit = new TicketUnit();
        unit.setStatus(UnitStatus.EXECUTING);
        unit.setIntentWritten(true);
        unit.setHeartbeatAt(heartbeatAt);
        unit.setExecutedAt(executedAt);
        return unit;
    }

    @Test
    void releaseLeaseToPending_清空租约字段() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertUnit(UnitStatus.PENDING, false, null);
        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));

        assertEquals(1, dao.releaseLeaseToPending(id));
        TicketUnit unit = dao.findById(id);
        assertEquals(UnitStatus.PENDING, unit.getStatus());
        assertNull(unit.getLeaseOwner());
        assertNull(unit.getLeaseUntil());
        assertNull(unit.getHeartbeatAt());

        // 释放后可被再次抢占
        assertEquals(1, dao.claimUnit(id, "worker-2", now.plusSeconds(30), now));
    }

    @Test
    void confirmFailed_executing状态置failed() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertUnit(UnitStatus.PENDING, false, null);
        dao.claimUnit(id, "worker-1", now.plusSeconds(30), now);
        dao.writeIntent(id, now);

        assertEquals(1, dao.confirmFailed(id, "connection lost", now.plusSeconds(1)));
        TicketUnit unit = dao.findById(id);
        assertEquals(UnitStatus.FAILED, unit.getStatus());
        assertEquals("connection lost", unit.getErrorMessage());
        assertNotNull(unit.getExecutedAt());
    }

    @Test
    void markNeedsConfirm_置状态与原因() {
        long id = insertUnit(UnitStatus.EXECUTING, true, null);
        assertEquals(1, dao.markNeedsConfirm(id, "heartbeat stale, SQL may have been executed"));
        TicketUnit unit = dao.findById(id);
        assertEquals(UnitStatus.NEEDS_CONFIRM, unit.getStatus());
        assertEquals("heartbeat stale, SQL may have been executed", unit.getErrorMessage());
    }

    @Test
    void listByFingerprintSince_按指纹与时间窗过滤() {
        LocalDateTime now = LocalDateTime.now();
        TicketUnit inWindow = new TicketUnit();
        inWindow.setStatus(UnitStatus.SUCCESS);
        inWindow.setExecFingerprint("fp-x");
        inWindow.setCreatedAt(now.minusMinutes(10));
        dao.insert(inWindow);

        TicketUnit outOfWindow = new TicketUnit();
        outOfWindow.setStatus(UnitStatus.SUCCESS);
        outOfWindow.setExecFingerprint("fp-x");
        outOfWindow.setCreatedAt(now.minusHours(2));
        dao.insert(outOfWindow);

        TicketUnit otherFingerprint = new TicketUnit();
        otherFingerprint.setStatus(UnitStatus.SUCCESS);
        otherFingerprint.setExecFingerprint("fp-y");
        otherFingerprint.setCreatedAt(now.minusMinutes(1));
        dao.insert(otherFingerprint);

        List<TicketUnit> hits = dao.listByFingerprintSince("fp-x", now.minusHours(1));
        assertEquals(1, hits.size());
        assertEquals("fp-x", hits.get(0).getExecFingerprint());
    }
}
