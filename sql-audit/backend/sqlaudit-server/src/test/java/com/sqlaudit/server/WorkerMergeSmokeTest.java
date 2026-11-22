package com.sqlaudit.server;

import com.sqlaudit.server.worker.WorkerLoopLifecycle;
import com.sqlaudit.worker.WorkerLoop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 并入 server 单进程的冒烟测试（h2 真库方言）。
 *
 * <p>验证：h2 profile 下上下文能起、{@link WorkerLoop} bean 存在、执行循环线程已随
 * server 启动（{@link WorkerLoopLifecycle} 的 ApplicationRunner 已驱动 startAsync）。</p>
 */
@SpringBootTest
@ActiveProfiles("h2")
class WorkerMergeSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads_withWorkerLoopBeanPresent() {
        WorkerLoop loop = context.getBean(WorkerLoop.class);
        assertNotNull(loop, "真库方言（h2）profile 下 WorkerLoop bean 应存在");
    }

    @Test
    void workerLoopLifecycleStartsLoopThread() {
        WorkerLoop loop = context.getBean(WorkerLoop.class);
        WorkerLoopLifecycle lifecycle = context.getBean(WorkerLoopLifecycle.class);
        assertNotNull(lifecycle, "worker 生命周期宿主 bean 应存在");
        assertTrue(loop.isRunning(), "worker 执行循环线程应已随 server 启动");
    }
}
