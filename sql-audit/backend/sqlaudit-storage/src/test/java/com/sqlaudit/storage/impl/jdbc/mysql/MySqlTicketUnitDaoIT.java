package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.model.TicketUnit;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MySqlTicketUnitDao} 对本地 Docker MySQL 的集成测试。
 *
 * <p>语义逐条对照内存版 {@code InMemoryTicketUnitDaoTest}（内存 24 条测试即协议规格）：
 * claim 硬条件 / writeIntent / confirmSuccess 幂等 0 / confirmFailed / markNeedsConfirm /
 * releaseLeaseToPending / reclaimExpiredLease / listClaimable / renewLease，
 * 另加两线程（加强为 8 线程）并发 claimUnit 同一 unit 只有一个成功。</p>
 *
 * <p><strong>与内存版测试的差异（DDL CHECK 约束所致）：</strong>内存版造数允许
 * {@code pending + intent_written=1} 的非法组合来验证硬条件；DDL §5 的 chk_unit_intent
 * 禁止该组合入库（不变式兜底），JDBC 版改用合法终态（success / needs_confirm + written）
 * 验证 claim 拒抢，并断言非法组合被 CHECK 拒绝。</p>
 */
@Tag("it")
class MySqlTicketUnitDaoIT {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static MySqlTicketUnitDao dao;

    @BeforeAll
    static void initDatabase() throws Exception {
        Assumptions.assumeTrue(MySqlTestSupport.mysqlAvailable(), "本地 MySQL 未启动，跳过集成测试");
        ds = MySqlTestSupport.buildDataSource();
        jdbc = new JdbcTemplate(ds);
        MySqlTestSupport.ensureSchema(ds);
        dao = new MySqlTicketUnitDao(jdbc);
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
            MySqlTestSupport.cleanAllTables(jdbc);
        }
    }

    /** DDL 为 DATETIME(3) 毫秒精度：断言相等前截断到毫秒，避免纳秒读写往返误差。 */
    private static LocalDateTime nowMillis() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** 插入一条满足 DDL NOT NULL 约束的 unit（内存版 fake 允许 null，真库必须填全必填列）。 */
    private static long insertUnit(String status, long ticketId, int unitOrder, String execFingerprint) {
        TicketUnit unit = new TicketUnit();
        unit.setTicketId(ticketId);
        unit.setInstanceId(1L);
        unit.setSchemaName("app");
        unit.setObjectName("t_user");
        unit.setUnitOrder(unitOrder);
        unit.setStatement("UPDATE t_user SET status=1 WHERE id=2".getBytes(StandardCharsets.UTF_8));
        unit.setStatementHash("a".repeat(64));
        unit.setStatementType("UPDATE");
        unit.setStatus(status);
        unit.setExecFingerprint(execFingerprint);
        unit.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return dao.insert(unit);
    }

    @Test
    void 全生命周期_pending到claim到writeIntent到confirmSuccess() {
        long id = insertUnit("pending", 1L, 1, "fp-default");
        LocalDateTime now = nowMillis();

        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));
        TicketUnit leased = dao.findById(id);
        assertEquals("leased", leased.getStatus());
        assertEquals("worker-1", leased.getLeaseOwner());
        assertEquals(now.plusSeconds(30), leased.getLeaseUntil());
        assertEquals(now, leased.getHeartbeatAt());

        assertEquals(1, dao.writeIntent(id, now));
        TicketUnit executing = dao.findById(id);
        assertEquals("executing", executing.getStatus());
        assertEquals(Boolean.TRUE, executing.getIntentWritten());

        assertEquals(1, dao.confirmSuccess(id, 42L, now.plusSeconds(2)));
        TicketUnit success = dao.findById(id);
        assertEquals("success", success.getStatus());
        assertEquals(42L, success.getAffectedRows());
        assertEquals(now.plusSeconds(2), success.getExecutedAt());
        assertNull(success.getLeaseOwner(), "成功后租约字段清空");
        assertNull(success.getLeaseUntil());
        assertNull(success.getHeartbeatAt());
        assertArrayEquals("UPDATE t_user SET status=1 WHERE id=2".getBytes(StandardCharsets.UTF_8),
                success.getStatement(), "加密 statement 字节原样往返");
        assertEquals("fp-default", success.getExecFingerprint());

        // 幂等确认：二次 confirmSuccess 返回 0，调用方视为成功
        assertEquals(0, dao.confirmSuccess(id, 42L, now.plusSeconds(3)));
    }

    @Test
    void claimUnit硬条件_已预写intent的终态unit抢不到() {
        LocalDateTime now = nowMillis();

        // 合法状态（DDL CHECK 允许）+ intent_written=1：claim 因状态与硬条件双双拒绝
        long failedId = insertUnit("failed", 1L, 1, "fp-default");
        jdbc.update("UPDATE t_ticket_unit SET intent_written=1 WHERE id=?", failedId);
        assertEquals(0, dao.claimUnit(failedId, "worker-1", now.plusSeconds(30), now));

        long needsConfirmId = insertUnit("needs_confirm", 1L, 2, "fp-default");
        jdbc.update("UPDATE t_ticket_unit SET intent_written=1 WHERE id=?", needsConfirmId);
        assertEquals(0, dao.claimUnit(needsConfirmId, "worker-1", now.plusSeconds(30), now));

        // 内存版的 pending+written 非法组合在 DDL 中被 chk_unit_intent 兜底拒绝（不变式验证）
        TicketUnit illegal = new TicketUnit();
        illegal.setTicketId(1L);
        illegal.setInstanceId(1L);
        illegal.setUnitOrder(1);
        illegal.setStatement("SELECT 1".getBytes(StandardCharsets.UTF_8));
        illegal.setStatementHash("b".repeat(64));
        illegal.setStatementType("SELECT");
        illegal.setStatus("pending");
        illegal.setIntentWritten(true);
        // 内存版的 pending+written 非法组合在 DDL 中被 chk_unit_intent 兜底拒绝（不变式验证）；
        // MySQL 错误码 3819（CHECK violated）经 Spring 翻译为 UncategorizedSQLException
        DataAccessException ex = assertThrows(DataAccessException.class, () -> dao.insert(illegal),
                "pending + intent_written=1 违反 chk_unit_intent");
        assertTrue(ex.getMessage().contains("chk_unit_intent"),
                "报错应指明违反的 CHECK 约束: " + ex.getMessage());
    }

    @Test
    void claimUnit_租约未过期的leased不可抢占_过期可抢占() {
        LocalDateTime now = nowMillis();

        // 未过期：先 claim 建立租约，第二个 worker 抢不到
        long freshId = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(freshId, "worker-1", now.plusSeconds(30), now));
        assertEquals(0, dao.claimUnit(freshId, "worker-2", now.plusSeconds(30), now));
        assertEquals("worker-1", dao.findById(freshId).getLeaseOwner());

        // 过期：用过去时间 claim 建立已过期租约，可被重新抢占，租约归属转移
        long expiredId = insertUnit("pending", 1L, 2, "fp-default");
        assertEquals(1, dao.claimUnit(expiredId, "worker-1", now.minusSeconds(1), now));
        assertEquals(1, dao.claimUnit(expiredId, "worker-2", now.plusSeconds(30), now));
        TicketUnit taken = dao.findById(expiredId);
        assertEquals("leased", taken.getStatus());
        assertEquals("worker-2", taken.getLeaseOwner());
    }

    @Test
    void 并发抢占_多线程claim同一unit只有一个成功() throws Exception {
        long id = insertUnit("pending", 1L, 1, "fp-default");
        LocalDateTime now = nowMillis();
        int threads = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger claimed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    claimed.addAndGet(dao.claimUnit(id, "worker-" + Thread.currentThread().getId(),
                            now.plusSeconds(30), now));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(10, TimeUnit.SECONDS), "并发抢占应全部完成");
        pool.shutdownNow();

        assertEquals(1, claimed.get(), "同一个 unit 只能被一个 worker 抢到（条件 UPDATE 行锁串行）");
        assertEquals("leased", dao.findById(id).getStatus());
    }

    @Test
    void reclaimExpiredLease_只回收intent未预写且租约过期的() {
        LocalDateTime now = nowMillis();

        // 可回收：leased + 过期 + intent_written=0
        long reclaimable = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(reclaimable, "worker-1", now.minusSeconds(1), now));

        // 不可回收：intent 已预写（executing，不进 pending/leased 回收分支）
        long written = insertUnit("pending", 1L, 2, "fp-default");
        assertEquals(1, dao.claimUnit(written, "worker-1", now.minusSeconds(1), now));
        assertEquals(1, dao.writeIntent(written, now));

        // 不可回收：租约未过期
        long fresh = insertUnit("pending", 1L, 3, "fp-default");
        assertEquals(1, dao.claimUnit(fresh, "worker-1", now.plusSeconds(60), now));

        assertEquals(1, dao.reclaimExpiredLease(now));

        TicketUnit reclaimed = dao.findById(reclaimable);
        assertEquals("pending", reclaimed.getStatus());
        assertNull(reclaimed.getLeaseOwner());
        assertNull(reclaimed.getLeaseUntil());
        assertNull(reclaimed.getHeartbeatAt());

        assertEquals("executing", dao.findById(written).getStatus(), "intent 已预写的不回收");
        assertEquals("leased", dao.findById(fresh).getStatus(), "租约未过期的不回收");
    }

    @Test
    void executing心跳停滞_走listExecutingWithStaleHeartbeat不进reclaim() {
        LocalDateTime now = nowMillis();

        // executing + 心跳停滞 + 未确认
        long staleId = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(staleId, "worker-1", now.plusSeconds(30), now.minusSeconds(300)));
        assertEquals(1, dao.writeIntent(staleId, now.minusSeconds(300)));

        // executing + 心跳新鲜
        long freshId = insertUnit("pending", 1L, 2, "fp-default");
        assertEquals(1, dao.claimUnit(freshId, "worker-1", now.plusSeconds(30), now.minusSeconds(1)));
        assertEquals(1, dao.writeIntent(freshId, now.minusSeconds(1)));

        // executing + 心跳停滞但已确认（confirmSuccess 后为 success，不在列表内）
        long confirmedId = insertUnit("pending", 1L, 3, "fp-default");
        assertEquals(1, dao.claimUnit(confirmedId, "worker-1", now.plusSeconds(30), now.minusSeconds(300)));
        assertEquals(1, dao.writeIntent(confirmedId, now.minusSeconds(300)));
        assertEquals(1, dao.confirmSuccess(confirmedId, 1L, now));

        assertEquals(0, dao.reclaimExpiredLease(now), "executing 状态不走租约回收");
        assertEquals("executing", dao.findById(staleId).getStatus());

        // 由 listExecutingWithStaleHeartbeat 分流（转 needs_confirm 由 reaper 决策）
        List<TicketUnit> staleList = dao.listExecutingWithStaleHeartbeat(now.minusSeconds(60));
        assertEquals(1, staleList.size());
        assertEquals(staleId, staleList.get(0).getId());
    }

    @Test
    void releaseLeaseToPending_清空租约字段且可被再次抢占() {
        LocalDateTime now = nowMillis();
        long id = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));

        assertEquals(1, dao.releaseLeaseToPending(id));
        TicketUnit unit = dao.findById(id);
        assertEquals("pending", unit.getStatus());
        assertNull(unit.getLeaseOwner());
        assertNull(unit.getLeaseUntil());
        assertNull(unit.getHeartbeatAt());

        // 非 leased 状态（已 pending）再 release 返回 0（JDBC 版条件 status='leased'）
        assertEquals(0, dao.releaseLeaseToPending(id));

        // 释放后可被其他 worker 抢占
        assertEquals(1, dao.claimUnit(id, "worker-2", now.plusSeconds(30), now));
    }

    @Test
    void confirmFailed_executing状态置failed() {
        LocalDateTime now = nowMillis();
        long id = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));
        assertEquals(1, dao.writeIntent(id, now));

        assertEquals(1, dao.confirmFailed(id, "connection lost", now.plusSeconds(1)));
        TicketUnit unit = dao.findById(id);
        assertEquals("failed", unit.getStatus());
        assertEquals("connection lost", unit.getErrorMessage());
        assertEquals(now.plusSeconds(1), unit.getExecutedAt());
        assertNull(unit.getLeaseOwner(), "失败后租约字段清空");
    }

    @Test
    void markNeedsConfirm_置状态与原因() {
        LocalDateTime now = nowMillis();
        long id = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));
        assertEquals(1, dao.writeIntent(id, now));

        assertEquals(1, dao.markNeedsConfirm(id, "heartbeat stale, SQL may have been executed"));
        TicketUnit unit = dao.findById(id);
        assertEquals("needs_confirm", unit.getStatus());
        assertEquals("heartbeat stale, SQL may have been executed", unit.getErrorMessage());
    }

    @Test
    void renewLease_仅本owner在executing状态可续租() {
        LocalDateTime now = nowMillis();
        long id = insertUnit("pending", 1L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(id, "worker-1", now.plusSeconds(30), now));
        assertEquals(1, dao.writeIntent(id, now));

        assertEquals(1, dao.renewLease(id, "worker-1", now.plusSeconds(35), now.plusSeconds(5)));
        TicketUnit unit = dao.findById(id);
        assertEquals(now.plusSeconds(35), unit.getLeaseUntil());
        assertEquals(now.plusSeconds(5), unit.getHeartbeatAt());

        // 非本 owner 续租失败
        assertEquals(0, dao.renewLease(id, "worker-2", now.plusSeconds(40), now.plusSeconds(6)));
        // 与内存版语义一致：status='executing' 才能续租；确认成功后（success）续租返回 0
        assertEquals(1, dao.confirmSuccess(id, 0L, now));
        assertEquals(0, dao.renewLease(id, "worker-1", now.plusSeconds(45), now));
    }

    @Test
    void listClaimable_候选过滤与ticket_order稳定排序与limit() {
        LocalDateTime now = nowMillis();
        long pending2 = insertUnit("pending", 2L, 1, "fp-default");
        long pending1 = insertUnit("pending", 1L, 2, "fp-default");
        long pending0 = insertUnit("pending", 1L, 1, "fp-default");

        // 过期 leased 可作候选
        long expiredLeased = insertUnit("pending", 3L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(expiredLeased, "worker-1", now.minusSeconds(1), now));

        // executing（intent 已预写）不作候选
        long executing = insertUnit("pending", 4L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(executing, "worker-1", now.plusSeconds(30), now));
        assertEquals(1, dao.writeIntent(executing, now));

        // 未过期 leased 不作候选
        long freshLeased = insertUnit("pending", 5L, 1, "fp-default");
        assertEquals(1, dao.claimUnit(freshLeased, "worker-1", now.plusSeconds(30), now));

        List<TicketUnit> claimable = dao.listClaimable(now, 10);
        assertEquals(4, claimable.size());
        // ORDER BY ticket_id, unit_order 稳定排序
        assertEquals(pending0, claimable.get(0).getId());
        assertEquals(pending1, claimable.get(1).getId());
        assertEquals(pending2, claimable.get(2).getId());
        assertEquals(expiredLeased, claimable.get(3).getId());

        assertEquals(1, dao.listClaimable(now, 1).size(), "limit 生效");
    }

    @Test
    void listByFingerprintSince_按指纹与时间窗过滤() {
        LocalDateTime now = nowMillis();
        insertUnit("success", 1L, 1, "fp-x");  // created_at = now-5min，窗口内
        TicketUnit outOfWindow = new TicketUnit();
        outOfWindow.setTicketId(1L);
        outOfWindow.setInstanceId(1L);
        outOfWindow.setUnitOrder(2);
        outOfWindow.setStatement("SELECT 1".getBytes(StandardCharsets.UTF_8));
        outOfWindow.setStatementHash("c".repeat(64));
        outOfWindow.setStatementType("SELECT");
        outOfWindow.setStatus("success");
        outOfWindow.setExecFingerprint("fp-x");
        outOfWindow.setCreatedAt(now.minusHours(2));
        dao.insert(outOfWindow);
        insertUnit("success", 1L, 3, "fp-y");  // 其他指纹

        List<TicketUnit> hits = dao.listByFingerprintSince("fp-x", now.minusHours(1));
        assertEquals(1, hits.size());
        assertEquals("fp-x", hits.get(0).getExecFingerprint());
        assertFalse(hits.get(0).getCreatedAt().isBefore(now.minusHours(1)));
    }
}
