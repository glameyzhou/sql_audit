package com.sqlaudit.server.config;

import com.sqlaudit.server.worker.WorkerLoopLifecycle;
import com.sqlaudit.worker.WorkerLoop;
import com.sqlaudit.worker.config.WorkerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * worker → server 单进程融合装配。
 *
 * <p>{@code @Import(WorkerConfig.class)} 把 worker 的执行循环全套 bean
 * （productionGateway / gatewayBridge / leaseReaper / unitExecutionEngine / workerLoop）
 * 引入 server 上下文；本配置声明 {@link WorkerLoopLifecycle}，在 server 启动后
 * 用 {@code ApplicationRunner} 启动执行循环、关闭时用 {@code destroyMethod = "stop"}
 * 优雅停机。</p>
 *
 * <p><strong>profile 控制</strong>：只在真库方言（mysql/postgres/h2）装配并启动 worker
 * 循环；默认 memory 不启动，避免单测环境 worker 抢跑。生产启动：
 * {@code SPRING_PROFILES_ACTIVE=prod,postgres}（或 {@code prod} group）一个进程同时跑
 * REST + 执行循环。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "postgres", "h2"})
@Import(WorkerConfig.class)
public class WorkerIntegrationConfig {

    @Bean(destroyMethod = "stop")
    public WorkerLoopLifecycle workerLoopLifecycle(WorkerLoop workerLoop) {
        return new WorkerLoopLifecycle(workerLoop);
    }
}
