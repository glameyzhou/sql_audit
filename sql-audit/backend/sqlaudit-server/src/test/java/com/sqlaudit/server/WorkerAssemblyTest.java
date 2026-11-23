package com.sqlaudit.server;

import com.sqlaudit.server.test.MySqlItBase;
import com.sqlaudit.server.worker.WorkerLoopLifecycle;
import com.sqlaudit.worker.WorkerLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 并入 server 的装配冒烟（profile it，{@code sqlaudit.worker.enabled=false}）：
 * worker 全套 bean（网关 / 元数据 / 指纹 / 执行循环）恒装配；执行循环生命周期宿主在开关
 * 关闭时不装配、循环不启动（自动化测试不被执行循环抢跑污染）。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class WorkerAssemblyTest extends MySqlItBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void transactionManagerAssembled() {
        assertNotNull(context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
                "DataSourceTransactionManager 应自动装配（@Transactional 依赖它）");
    }

    @Test
    void workerBeansAssembled_withoutStartingLoop() {
        WorkerLoop loop = context.getBean(WorkerLoop.class);
        assertNotNull(loop, "worker 执行循环 bean 应恒装配");
        assertTrue(!loop.isRunning(), "开关关闭时执行循环不得启动");

        // 生命周期宿主（ApplicationRunner 驱动 startAsync）不装配
        WorkerLoopLifecycle lifecycle = context.getBeanProvider(WorkerLoopLifecycle.class).getIfAvailable();
        assertNull(lifecycle, "sqlaudit.worker.enabled=false 时不应装配 WorkerLoopLifecycle");

        // 真实元数据 / 指纹提供者（worker 侧真实现）装配而非 stub
        assertNotNull(context.getBean("tableMetadataProvider"),
                "表元数据提供者应来自 worker 真实现");
    }
}
