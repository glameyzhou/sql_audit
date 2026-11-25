package com.sqlaudit.worker;

import com.sqlaudit.executor.engine.UnitExecutionEngine;
import com.sqlaudit.executor.reaper.LeaseReaper;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Worker 主循环（README §4.2：轮询 + 租约 + 心跳；README §3.1：worker 是唯一连生产库的进程）。
 *
 * <p>循环骨架（每 {@code pollInterval} 一轮）：</p>
 * <ol>
 *   <li>{@link LeaseReaper#reap}：本轮回收（安全回 pending / 转 needs_confirm / 清过期锁）；</li>
 *   <li>{@code listClaimable} 取候选（limit 10），逐个过 {@code ticketCanExecute} 过滤
 *       （README §4.2 前置门禁：未过审批 / 需二次确认未确认的工单直接跳过，不抢占租约、
 *       不动状态——堵上「未过审批的单子被直接执行」）；</li>
 *   <li>过滤后的 unit 逐个 {@link UnitExecutionEngine#execute}
 *       竞争执行（claimUnit 硬条件保证多 worker 安全）；</li>
 *   <li>长任务心跳：执行线程内每 {@code heartbeatInterval} 续租（引擎执行是同步调用，
 *       W2 接真生产库后由 ProductionGateway 实现在事务内回调续租——DDL 可能跑几十分钟，
 *       只认心跳不认耗时的 reaper 才不会误杀）。</li>
 * </ol>
 *
 * <p>本类不持有线程——由宿主（server 内嵌模式 / 独立进程 main）调用
 * {@link #runOnce} 或 {@link #startAsync} 驱动，测试直接 runOnce。</p>
 */
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    /** 轮询间隔（README §4.2：worker 每秒轮询）。 */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /** 心跳间隔（README §4.2：心跳 5s；reaper TTL = 15s ≥ 3 个心跳周期）。 */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /** 单轮最多尝试的候选 unit 数。 */
    private static final int BATCH_LIMIT = 10;

    private final TicketUnitDao unitDao;
    private final UnitExecutionEngine engine;
    private final LeaseReaper reaper;
    private final String workerId;
    /** README §4.2 前置门禁：工单是否已到可执行态（审批通过且完成必要的二次确认）。 */
    private final Predicate<Long> ticketCanExecute;
    /** 执行单元结束后同步工单主状态的回调（可选，默认空操作）。 */
    private final Consumer<Long> onUnitExecuted;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;

    public WorkerLoop(TicketUnitDao unitDao, UnitExecutionEngine engine,
                      LeaseReaper reaper, String workerId, Predicate<Long> ticketCanExecute) {
        this(unitDao, engine, reaper, workerId, ticketCanExecute, null);
    }

    public WorkerLoop(TicketUnitDao unitDao, UnitExecutionEngine engine,
                      LeaseReaper reaper, String workerId, Predicate<Long> ticketCanExecute,
                      Consumer<Long> onUnitExecuted) {
        this.unitDao = Objects.requireNonNull(unitDao);
        this.engine = Objects.requireNonNull(engine);
        this.reaper = Objects.requireNonNull(reaper);
        this.workerId = Objects.requireNonNull(workerId);
        this.ticketCanExecute = Objects.requireNonNull(ticketCanExecute);
        this.onUnitExecuted = onUnitExecuted;
    }

    /** 单轮执行（reap → 取候选 → canExecute 过滤 → 逐个竞争执行 → 同步工单状态）。返回本轮执行成功的 unit 数。 */
    public int runOnce(LocalDateTime now) {
        reaper.reap(now);

        List<TicketUnit> candidates = unitDao.listClaimableForExecutableTickets(now, BATCH_LIMIT);
        int executed = 0;
        for (TicketUnit unit : candidates) {
            // README §4.2 前置门禁：未过审批（含需二次确认未确认）的工单跳过且不动状态，
            // 堵上「未过审批的单子被直接执行」——审批流推进后下一轮自然恢复
            boolean canExecute;
            try {
                canExecute = ticketCanExecute.test(unit.getTicketId());
            } catch (Exception e) {
                // 谓词异常（如孤儿 unit 的工单已删）不打断整轮：按不可执行跳过，
                // 否则同一候选会让每一轮都在此处中断、饿死后续候选
                log.warn("[worker-{}] canExecute 判定异常，跳过 unit={} ticket={}: {}",
                        workerId, unit.getId(), unit.getTicketId(), e.getMessage());
                continue;
            }
            if (!canExecute) {
                log.debug("[worker-{}] 工单未到可执行态，跳过不动状态 unit={} ticket={}",
                        workerId, unit.getId(), unit.getTicketId());
                continue;
            }
            UnitExecutionEngine.EngineResult result;
            try {
                result = engine.execute(unit, workerId, now);
            } catch (Exception e) {
                // 执行期异常（如指纹门禁的 ro_meta 连接失败）不打断整轮：unit 状态
                // 由租约回收器兜底（executing + 心跳停滞 → needs_confirm 人工核对）
                log.error("[worker-{}] 执行异常 unit={}: {}", workerId, unit.getId(), e.getMessage());
                continue;
            }
            if (result.status() == UnitExecutionEngine.EngineResult.Status.SUCCESS) {
                executed++;
            }
            if (onUnitExecuted != null
                    && result.status() != UnitExecutionEngine.EngineResult.Status.NOT_CLAIMED
                    && result.status() != UnitExecutionEngine.EngineResult.Status.LOCK_BUSY
                    && result.status() != UnitExecutionEngine.EngineResult.Status.INTENT_REJECTED) {
                try {
                    onUnitExecuted.accept(unit.getTicketId());
                } catch (Exception e) {
                    log.warn("[worker-{}] 同步工单状态失败 ticket={}: {}",
                            workerId, unit.getTicketId(), e.getMessage());
                }
            }
        }
        return executed;
    }

    /** 异步启动（内嵌模式：server 进程里跑；生产建议独立进程 + k8s 多副本）。 */
    public Thread startAsync() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("worker 已启动");
        }
        Thread t = new Thread(() -> {
            while (running.get()) {
                try {
                    runOnce(LocalDateTime.now());
                } catch (Exception e) {
                    // 单轮异常不退出循环（worker 可用性优先）；W2 后段接企微告警
                    log.error("[worker-{}] 轮次异常", workerId, e);
                }
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "worker-loop-" + workerId);
        t.setDaemon(true);
        this.loopThread = t;
        t.start();
        return t;
    }

    /** 停止（优雅退出：置 running=false 并中断休眠，当前轮跑完后结束）。 */
    public void stop() {
        running.set(false);
        Thread t = loopThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** 是否正在运行（观测 / 测试用）。 */
    public boolean isRunning() {
        return running.get();
    }
}
