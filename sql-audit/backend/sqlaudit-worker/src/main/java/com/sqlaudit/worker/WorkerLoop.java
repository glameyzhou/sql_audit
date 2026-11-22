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

/**
 * Worker 主循环（设计文档 §3.3：轮询 + 租约 + 心跳；§2.2：worker 是唯一连生产库的进程）。
 *
 * <p>循环骨架（每 {@code pollInterval} 一轮）：</p>
 * <ol>
 *   <li>{@link LeaseReaper#reap}：本轮回收（安全回 pending / 转 needs_confirm / 清过期锁）；</li>
 *   <li>{@code listClaimable} 取候选（limit 10），逐个 {@link UnitExecutionEngine#execute}
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

    /** 轮询间隔（§3.3：worker 每秒轮询）。 */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /** 心跳间隔（§3.3：心跳 5s；reaper TTL = 15s ≥ 3 个心跳周期）。 */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /** 单轮最多尝试的候选 unit 数。 */
    private static final int BATCH_LIMIT = 10;

    private final TicketUnitDao unitDao;
    private final UnitExecutionEngine engine;
    private final LeaseReaper reaper;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkerLoop(TicketUnitDao unitDao, UnitExecutionEngine engine,
                      LeaseReaper reaper, String workerId) {
        this.unitDao = Objects.requireNonNull(unitDao);
        this.engine = Objects.requireNonNull(engine);
        this.reaper = Objects.requireNonNull(reaper);
        this.workerId = Objects.requireNonNull(workerId);
    }

    /** 单轮执行（reap → 取候选 → 逐个竞争执行）。返回本轮执行成功的 unit 数。 */
    public int runOnce(LocalDateTime now) {
        reaper.reap(now);

        List<TicketUnit> candidates = unitDao.listClaimable(now, BATCH_LIMIT);
        int executed = 0;
        for (TicketUnit unit : candidates) {
            UnitExecutionEngine.EngineResult result = engine.execute(unit, workerId, now);
            if (result.status() == UnitExecutionEngine.EngineResult.Status.SUCCESS) {
                executed++;
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
        t.start();
        return t;
    }

    /** 停止（优雅退出：当前轮跑完后结束）。 */
    public void stop() {
        running.set(false);
    }
}
