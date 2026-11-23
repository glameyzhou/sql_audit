package com.sqlaudit.executor.engine;

import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.executor.gate.GateContext;
import com.sqlaudit.storage.dao.ExecLockDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.ExecLock;
import com.sqlaudit.storage.model.TicketUnit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 执行单元引擎——README §4.2 抢占与执行协议的编排核心（最高优先级工程约束）。
 *
 * <p>协议步骤（每一步的失败路径都有定义，不静默吞）：</p>
 * <ol>
 *   <li><strong>抢占</strong>：claimUnit 硬条件 = intent_written=0 + status ∈ (pending,
 *       过期 leased)。抢不到直接放弃（其他 worker 在跑）；</li>
 *   <li><strong>抢执行锁</strong>（README §4.2）：实例级 exec_lock，同实例同时只跑一个 unit；
 *       拿不到 → 释放租约退回 pending 等下一轮；</li>
 *   <li><strong>预写 intent</strong>：status='executing' + intent_written=1 同事务写入
 *       ——<strong>事务提交后才允许连生产库</strong>（SQL 可能已到生产库的标记）；</li>
 *   <li><strong>README §4.2 执行前门禁</strong>：writeIntent 提交后、连生产库之前，复核审批时效 /
 *       结构漂移 / 回滚能力（并发互斥已由 exec_lock 保证）；不过 → onGateFailure 补偿 +
 *       confirmFailed 置 failed + 返回 GATED，<strong>绝不连生产库</strong>；</li>
 *   <li><strong>执行</strong>：ProductionGateway 单事务（前镜像 → DML → 对账 → COMMIT）；</li>
 *   <li><strong>幂等确认</strong>：confirmSuccess 条件 UPDATE（status='executing' 且
 *       executed_at IS NULL），影响行数 0 = 已有人确认过，直接返回成功（不重复执行）。</li>
 * </ol>
 *
 * <p>失败路径（README §4.2 协议表）：</p>
 * <ul>
 *   <li>RECONCILE_FAILED → markNeedsConfirm（<strong>禁止自动重试</strong>）；</li>
 *   <li>EXECUTION_FAILED → confirmFailed（事务已回滚，可安全重跑）；</li>
 *   <li>反向 SQL 落库失败 → markNeedsConfirm（生产库已改，平台库缺回滚数据，
 *       人工兜底——宁可 needs_confirm 也不丢回滚能力）。</li>
 * </ul>
 *
 * <p>协议的正确性依赖 DAO 的条件更新语义（W0-3 契约 + 内存 fake 已验证）；
 * 本类不直接持有 JDBC。</p>
 */
public class UnitExecutionEngine {

    /** 租约时长（README §4.2：租约到期 = now + 30s）。 */
    public static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final TicketUnitDao ticketUnitDao;
    private final ExecLockDao execLockDao;
    private final RollbackDao rollbackDao;
    private final ProductionGatewayBridge gatewayBridge;
    private final RollbackPersister rollbackPersister;
    private final ExecutionGate gate;
    private final GateContext gateContext;

    public UnitExecutionEngine(TicketUnitDao ticketUnitDao,
                               ExecLockDao execLockDao,
                               RollbackDao rollbackDao,
                               ProductionGatewayBridge gatewayBridge,
                               RollbackPersister rollbackPersister,
                               ExecutionGate gate,
                               GateContext gateContext) {
        this.ticketUnitDao = ticketUnitDao;
        this.execLockDao = execLockDao;
        this.rollbackDao = rollbackDao;
        this.gatewayBridge = gatewayBridge;
        this.rollbackPersister = rollbackPersister;
        this.gate = gate;
        this.gateContext = gateContext;
    }

    /**
     * 尝试执行一个 unit（worker 轮询入口）。
     *
     * @return 执行结局
     */
    public EngineResult execute(TicketUnit unit, String workerId, LocalDateTime now) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(workerId, "workerId");

        // 1. 抢占租约（硬条件 intent_written=0 在 DAO 条件更新里保证）
        LocalDateTime leaseUntil = now.plus(LEASE_TTL);
        int claimed = ticketUnitDao.claimUnit(unit.getId(), workerId, leaseUntil, now);
        if (claimed != 1) {
            return EngineResult.notClaimed();
        }

        // 2. 实例级执行锁（README §4.2 并发互斥）：拿不到 → 释放租约退回 pending
        String lockKey = "inst:" + unit.getInstanceId();
        ExecLock lock = new ExecLock();
        lock.setLockKey(lockKey);
        lock.setUnitId(unit.getId());
        lock.setWorkerId(workerId);
        lock.setAcquiredAt(now);
        lock.setExpiresAt(leaseUntil);
        if (!execLockDao.tryAcquire(lock)) {
            ticketUnitDao.releaseLeaseToPending(unit.getId());
            return EngineResult.lockBusy();
        }

        try {
            // 3. 预写 intent：status=executing + intent_written=1（提交后才允许连生产库）
            int intent = ticketUnitDao.writeIntent(unit.getId(), now);
            if (intent != 1) {
                // 状态不满足（如已被 reaper 转 needs_confirm）——放弃，不连生产库
                return EngineResult.intentRejected();
            }

            // 3.5 执行前门禁：审批时效 / 结构漂移 / 回滚能力复核（并发互斥已由 exec_lock 保证）
            ExecutionGate.GateResult gateResult = gate.check(unit,
                    gateContext.approvedAt(unit), gateContext.rollbackStillAvailable(unit), now);
            if (!gateResult.isPassed()) {
                gateContext.onGateFailure(unit, gateResult);   // 补偿动作（如 TTL 超时置工单 expired）
                ticketUnitDao.confirmFailed(unit.getId(),
                        gateResult.failedGate().getCode() + ": " + gateResult.reason(), now);
                return EngineResult.gated(gateResult.failedGate().getCode(), gateResult.reason());
            }

            // 4. 生产库单事务执行（前镜像 → DML → 对账）
            com.sqlaudit.executor.gateway.ProductionGateway.ExecutionResult exec = gatewayBridge.execute(unit);

        switch (exec.outcome()) {
            case SUCCESS -> {
                // 5a. 反向 SQL 落库（用前镜像补全），失败 → needs_confirm（保回滚能力优先）
                if (!rollbackPersister.persist(unit, exec)) {
                    ticketUnitDao.markNeedsConfirm(unit.getId(),
                            "反向 SQL 落库失败，生产库已变更，人工核对并补录回滚");
                    return EngineResult.needsConfirm("反向 SQL 落库失败");
                }
                // 5b. 幂等确认（条件 UPDATE；影响行数 0 = 已确认过，仍返回成功）
                ticketUnitDao.confirmSuccess(unit.getId(), exec.affectedRows(), now);
                return EngineResult.success(exec.affectedRows());
            }
            case RECONCILE_FAILED -> {
                // 对账失败：事务已回滚，但「回滚是否干净」不确定 → needs_confirm，
                // 禁止自动重试（README §4.4 硬门禁 + README §4.2 协议表）
                ticketUnitDao.markNeedsConfirm(unit.getId(), exec.errorMessage());
                return EngineResult.needsConfirm(exec.errorMessage());
            }
            case EXECUTION_FAILED -> {
                // 执行异常：事务已回滚，SQL 未生效，可安全重跑
                ticketUnitDao.confirmFailed(unit.getId(), exec.errorMessage(), now);
                return EngineResult.failed(exec.errorMessage());
            }
            default -> {
                ticketUnitDao.markNeedsConfirm(unit.getId(), "未知执行结局，人工核对");
                return EngineResult.needsConfirm("未知执行结局");
            }
        }
        } finally {
            // 锁与租约同生命周期（README §4.2）：无论成败释放执行锁
            execLockDao.release(lockKey, unit.getId());
        }
    }

    /** 引擎结局。 */
    public record EngineResult(
            Status status,
            long affectedRows,
            String message) {

        public enum Status {
            /** 未抢到（其他 worker 在跑或状态不允许） */
            NOT_CLAIMED,
            /** 实例锁被占，已退回 pending 等下一轮 */
            LOCK_BUSY,
            /** intent 预写被拒（状态已流转） */
            INTENT_REJECTED,
            /** 执行前门禁拦截（审批时效 / 结构漂移 / 回滚能力，README §4.2） */
            GATED,
            /** 执行成功 */
            SUCCESS,
            /** 执行失败（可安全重跑） */
            FAILED,
            /** 需人工确认（禁止自动重试） */
            NEEDS_CONFIRM
        }

        static EngineResult notClaimed() {
            return new EngineResult(Status.NOT_CLAIMED, 0, null);
        }

        static EngineResult lockBusy() {
            return new EngineResult(Status.LOCK_BUSY, 0, null);
        }

        static EngineResult intentRejected() {
            return new EngineResult(Status.INTENT_REJECTED, 0, null);
        }

        /**
         * 门禁拦截结局。{@code gateCode} 是门禁码的字符串形式（只做 message 前缀，
         * 形如 {@code R_SCHEMA_DRIFT: 原因}），不是门禁身份——门禁身份在
         * {@code ExecutionGate.GateResult.failedGate()} 上。
         */
        static EngineResult gated(String gateCode, String reason) {
            return new EngineResult(Status.GATED, 0, gateCode + ": " + reason);
        }

        static EngineResult success(long affectedRows) {
            return new EngineResult(Status.SUCCESS, affectedRows, null);
        }

        static EngineResult failed(String message) {
            return new EngineResult(Status.FAILED, 0, message);
        }

        static EngineResult needsConfirm(String message) {
            return new EngineResult(Status.NEEDS_CONFIRM, 0, message);
        }
    }

    /**
     * 生产库执行桥：把 unit（明文语句 + 方言信息）交给 ProductionGateway 在单事务内执行。
     * W2 装配时组合 parser（构造前镜像 SQL）+ ProductionGateway 的 JDBC 实现。
     */
    public interface ProductionGatewayBridge {

        com.sqlaudit.executor.gateway.ProductionGateway.ExecutionResult execute(TicketUnit unit);
    }

    /** 反向 SQL 落库（用执行结果里的前镜像生成并持久化；返回是否成功）。 */
    public interface RollbackPersister {

        boolean persist(TicketUnit unit,
                        com.sqlaudit.executor.gateway.ProductionGateway.ExecutionResult result);
    }
}
