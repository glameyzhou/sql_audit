package com.sqlaudit.server.worker;

import com.sqlaudit.worker.WorkerLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Worker 执行循环的生命周期宿主（内嵌单进程模式）。
 *
 * <p>实现 {@link ApplicationRunner}：server 上下文就绪后调用 {@link WorkerLoop#startAsync}
 * 启动执行循环线程；Spring 关闭时由 bean 的 {@code destroyMethod = "stop"} 触发
 * {@link #stop()}，置停并中断休眠、等待当前轮收尾——与 REST 生命周期同进同退。</p>
 *
 * <p>本类仅在开关 {@code sqlaudit.worker.enabled}（默认 true）打开时装配（见
 * {@code WorkerIntegrationConfig}）；开关关闭时只装配 worker bean、不启动循环线程
 * （自动化测试 profile it 即此形态）。</p>
 */
public class WorkerLoopLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoopLifecycle.class);

    private static final long SHUTDOWN_JOIN_MILLIS = 2_000;

    private final WorkerLoop workerLoop;
    private Thread loopThread;

    public WorkerLoopLifecycle(WorkerLoop workerLoop) {
        this.workerLoop = workerLoop;
    }

    @Override
    public void run(ApplicationArguments args) {
        loopThread = workerLoop.startAsync();
        log.info("worker 执行循环已随 server 启动（单进程内嵌）");
    }

    /** 优雅停机：置停 worker 循环并等待当前轮收尾（由 destroyMethod 触发）。 */
    public void stop() {
        workerLoop.stop();
        if (loopThread != null) {
            try {
                loopThread.join(SHUTDOWN_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("worker 执行循环已优雅停止");
    }
}
