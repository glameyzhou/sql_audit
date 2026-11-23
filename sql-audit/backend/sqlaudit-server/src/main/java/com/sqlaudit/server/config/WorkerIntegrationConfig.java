package com.sqlaudit.server.config;

import com.sqlaudit.server.worker.WorkerLoopLifecycle;
import com.sqlaudit.worker.WorkerLoop;
import com.sqlaudit.worker.config.WorkerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * worker → server 单进程融合装配。
 *
 * <p>{@code @Import(WorkerConfig.class)} 把 worker 的执行循环全套 bean
 * （productionGateway / gatewayBridge / leaseReaper / unitExecutionEngine / workerLoop）
 * 引入 server 上下文——平台库只剩 mysql / postgres 两个真库方言，worker 的网关 / 元数据 /
 * 指纹 bean <strong>恒装配</strong>（不再按 profile 条件装配）；只有执行循环的启动受开关
 * {@code sqlaudit.worker.enabled}（默认 true）控制：本配置声明的
 * {@link WorkerLoopLifecycle} 在该开关打开时装配，server 启动后用 {@code ApplicationRunner}
 * 启动执行循环、关闭时用 {@code destroyMethod = "stop"} 优雅停机。开关关闭时（如自动化
 * 测试 profile it）只装配 bean、不启动循环线程。</p>
 *
 * <p>生产启动：{@code SPRING_PROFILES_ACTIVE=prod,postgres}（或 {@code prod} group）一个
 * 进程同时跑 REST + 执行循环。</p>
 */
@Configuration(proxyBeanMethods = false)
@Import(WorkerConfig.class)
public class WorkerIntegrationConfig {

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "sqlaudit.worker.enabled", havingValue = "true", matchIfMissing = true)
    public WorkerLoopLifecycle workerLoopLifecycle(WorkerLoop workerLoop) {
        return new WorkerLoopLifecycle(workerLoop);
    }
}
