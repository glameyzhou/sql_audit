package com.sqlaudit.executor.reaper;

import com.sqlaudit.storage.dao.ExecLockDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租约回收器（设计文档 §3.3 reaper 分流表 + §3.5 锁清理）。
 *
 * <p><strong>reaper 独立于抢占</strong>（v2 漏洞修正：抢占 SQL 只覆盖 pending，
 * 崩在 executing 的行永远回收不到）。分流规则：</p>
 * <table>
 *   <tr><th>现场</th><th>判定</th><th>动作</th></tr>
 *   <tr><td>pending/leased + 租约过期 + intent_written=0</td><td>SQL 一定没发出</td>
 *       <td>清租约回 pending，可安全重跑</td></tr>
 *   <tr><td>executing + 心跳停滞超 TTL + executed_at IS NULL</td><td>SQL 可能已执行</td>
 *       <td>转 needs_confirm，禁止自动重试，告警</td></tr>
 *   <tr><td>executing + 心跳正常</td><td>长事务 DDL 正常执行中</td>
 *       <td>不回收；超 max_exec_minutes 只告警</td></tr>
 * </table>
 *
 * <p><strong>只认心跳不认耗时</strong>（§3.3）：长时间 DDL 会让 unit 长期停在
 * executing，按耗时回收会把正常执行的 DDL 误判为死 worker。</p>
 */
public class LeaseReaper {

    /** 心跳停滞判定窗口（TTL ≥ 心跳间隔 × 3，§3.3：心跳 5s → TTL 15s）。 */
    public static final Duration HEARTBEAT_STALE_TTL = Duration.ofSeconds(15);

    private final TicketUnitDao ticketUnitDao;
    private final ExecLockDao execLockDao;

    public LeaseReaper(TicketUnitDao ticketUnitDao, ExecLockDao execLockDao) {
        this.ticketUnitDao = ticketUnitDao;
        this.execLockDao = execLockDao;
    }

    /** 回收结果统计（观测与告警路由用）。 */
    public record ReapResult(int reclaimedToPending, int markedNeedsConfirm) {

        public int total() {
            return reclaimedToPending + markedNeedsConfirm;
        }
    }

    /**
     * 执行一轮回收。
     *
     * @param now 当前时间
     * @return 分流统计（needs_confirm &gt; 0 应触发企微告警，§11）
     */
    public ReapResult reap(LocalDateTime now) {
        // 1. 安全回收：pending/leased + 租约过期 + intent_written=0 → 回 pending
        int reclaimed = ticketUnitDao.reclaimExpiredLease(now);

        // 2. 疑似执行中死亡：executing + 心跳停滞 + 未确认 → needs_confirm（禁止重试）
        LocalDateTime staleThreshold = now.minus(HEARTBEAT_STALE_TTL);
        List<TicketUnit> stale = ticketUnitDao.listExecutingWithStaleHeartbeat(staleThreshold);
        int needsConfirm = 0;
        for (TicketUnit unit : stale) {
            // 双保险：已确认的（executed_at 非空）不转
            if (unit.getExecutedAt() == null && Boolean.TRUE.equals(unit.getIntentWritten())) {
                ticketUnitDao.markNeedsConfirm(unit.getId(),
                        "心跳停滞超 TTL 且 intent 已预写，SQL 可能已执行，禁止自动重试（§3.3）");
                needsConfirm++;
            }
        }

        // 3. 过期执行锁清理（§3.5：锁与租约同生命周期，worker 崩溃后防锁永久泄漏）
        execLockDao.cleanupExpired(now);

        return new ReapResult(reclaimed, needsConfirm);
    }
}
