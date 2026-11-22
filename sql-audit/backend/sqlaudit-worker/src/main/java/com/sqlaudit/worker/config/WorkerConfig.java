package com.sqlaudit.worker.config;

import com.sqlaudit.executor.engine.UnitExecutionEngine;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.executor.reaper.LeaseReaper;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rollback.lifecycle.RollbackLifecycleService;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.ExecLockDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.worker.WorkerLoop;
import com.sqlaudit.worker.gateway.GatewayBridgeImpl;
import com.sqlaudit.worker.gateway.JdbcProductionGateway;
import com.sqlaudit.worker.gateway.JdbcTableMetadataProvider;
import com.sqlaudit.worker.gateway.JdbcStructureFingerprintProvider;
import com.sqlaudit.worker.gateway.WorkerGateContext;
import com.sqlaudit.worker.rollback.RollbackPersisterImpl;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Worker 生产装配：注册 {@link JdbcProductionGateway}（生产库真执行网关）、执行前门禁
 * （§3.5：{@link ExecutionGate} + 指纹提供者 + 门禁上下文）与执行循环所需的编排 bean
 * （网关桥 / 反向 SQL 落库 / 租约回收 / 执行引擎 / {@link WorkerLoop}）。
 *
 * <p><strong>worker 已并入 server 单进程</strong>：本配置不再由独立 worker 进程加载，
 * 而是由 server 的 {@code WorkerIntegrationConfig} 通过 {@code @Import} 引入——
 * {@code SPRING_PROFILES_ACTIVE=prod,postgres}（或 {@code prod} profile group）时，
 * 一个进程同时跑 REST API + worker 执行循环。生产无需再单独部署 worker 进程。</p>
 *
 * <p><strong>profile 控制</strong>：本类 {@code @Profile({"mysql","postgres","h2"})}，
 * 只在真库方言下装配 worker 全套 bean；默认 {@code memory} profile 无 {@code ExecLockDao}
 * 等 JDBC 方言 DAO，不装配，避免单测环境 worker 循环抢跑、污染测试。</p>
 *
 * <p>依赖宿主（server 各 profile 装配）提供：{@code TicketUnitDao}/{@code ExecLockDao}/
 * {@code RollbackDao}/{@code InstanceDao}/{@code ApprovalDao}/{@code TicketDao}/
 * {@code CredentialDao}（mysql/postgres/h2 各自的 JDBC DAO）、平台库 {@code JdbcTemplate}
 * （Spring Boot 自动配置）、{@code SqlParserService}/{@code TableMetadataProvider}/
 * {@code RuleEngine}/{@code RollbackCapabilityAssessor}/{@code ApprovalFlowEngine}
 * （server 公共装配）。本类只声明 worker 侧组合关系，不负责平台库连接本身。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "postgres", "h2"})
public class WorkerConfig {

    /**
     * 生产库真执行网关（§2.2：worker 是唯一连生产库的进程，server 内嵌后仍是本 bean 独享）。
     *
     * <p><strong>bean 名是 {@code workerProductionGateway}</strong>（方法名即 bean 名）：
     * server 侧 {@code ServerBeansConfig} 另有一个同名语义的 {@code productionGateway}
     * （回滚执行网关，无前镜像路径）。两者都实现
     * {@link com.sqlaudit.executor.gateway.ProductionGateway} 接口、在
     * mysql/postgres/h2 profile 下共存——Spring Boot 3 默认禁止 bean 定义覆盖，同名会直接
     * 启动失败，故必须区分；注入按各自具体类解析，不按接口。</p>
     */
    @Bean
    public JdbcProductionGateway workerProductionGateway(InstanceDao instanceDao, JdbcTemplate jdbcTemplate) {
        return new JdbcProductionGateway(instanceDao, jdbcTemplate);
    }

    /** 执行桥：unit 明文 → parser 解析 → 现场构造前镜像 → 交网关单事务执行。 */
    @Bean
    public UnitExecutionEngine.ProductionGatewayBridge gatewayBridge(
            JdbcProductionGateway productionGateway, SqlParserService parser, TableMetadataProvider metadata) {
        return new GatewayBridgeImpl(productionGateway, parser, metadata);
    }

    /** 反向 SQL 生命周期服务（生成 → 语法自检 → verified，§5.9）。 */
    @Bean
    public RollbackLifecycleService rollbackLifecycleService(RollbackDao rollbackDao, SqlParserService parser) {
        return new RollbackLifecycleService(rollbackDao, parser);
    }

    /** 反向 SQL 落库桥（执行成功后用前镜像生成反向语句持久化，§5.3 步骤 4）。 */
    @Bean
    public UnitExecutionEngine.RollbackPersister rollbackPersister(
            RollbackLifecycleService lifecycle, SqlParserService parser, TableMetadataProvider metadata) {
        return new RollbackPersisterImpl(lifecycle, parser, metadata);
    }

    /** 租约回收器（reap：安全回 pending / 转 needs_confirm / 清过期锁，§3.3）。 */
    @Bean
    public LeaseReaper leaseReaper(TicketUnitDao ticketUnitDao, ExecLockDao execLockDao) {
        return new LeaseReaper(ticketUnitDao, execLockDao);
    }

    /** 结构指纹提供者（§3.5 结构漂移门禁：RO_META 连库重算 SHOW CREATE / pg_get_tabledef）。 */
    @Bean
    public JdbcStructureFingerprintProvider structureFingerprintProvider(
            InstanceDao instanceDao, CredentialDao credentialDao) {
        return new JdbcStructureFingerprintProvider(instanceDao, credentialDao);
    }

    /** 生产库表元数据真实现（RO_META + information_schema），供规则引擎 / 前镜像 /
     *  回滚复核取真实表结构；memory profile 下由 ServerBeansConfig 的 dev stub 提供。 */
    @Bean
    public JdbcTableMetadataProvider tableMetadataProvider(
            InstanceDao instanceDao, CredentialDao credentialDao) {
        return new JdbcTableMetadataProvider(instanceDao, credentialDao);
    }

    /** 执行前门禁（§3.5：审批时效 / 结构漂移 / 回滚能力复核，任一不过不执行）。 */
    @Bean
    public ExecutionGate executionGate(JdbcStructureFingerprintProvider structureFingerprintProvider) {
        return new ExecutionGate(structureFingerprintProvider);
    }

    /** 门禁上下文（§3.5 worker 侧装配：审批时间 + 回滚能力复核 + 超时置 expired 补偿）。 */
    @Bean
    public WorkerGateContext workerGateContext(
            ApprovalDao approvalDao, TicketDao ticketDao, InstanceDao instanceDao,
            SqlParserService parser, RuleEngine ruleEngine,
            RollbackCapabilityAssessor rollbackCapabilityAssessor, TableMetadataProvider metadata) {
        return new WorkerGateContext(approvalDao, ticketDao, instanceDao, parser,
                ruleEngine, rollbackCapabilityAssessor, metadata);
    }

    /** 执行单元引擎（抢占 → 实例锁 → 门禁 → 预写 intent → 执行 → 幂等确认，§3.3 协议核心）。 */
    @Bean
    public UnitExecutionEngine unitExecutionEngine(
            TicketUnitDao ticketUnitDao, ExecLockDao execLockDao, RollbackDao rollbackDao,
            UnitExecutionEngine.ProductionGatewayBridge gatewayBridge,
            UnitExecutionEngine.RollbackPersister rollbackPersister,
            ExecutionGate executionGate, WorkerGateContext workerGateContext) {
        return new UnitExecutionEngine(ticketUnitDao, execLockDao, rollbackDao,
                gatewayBridge, rollbackPersister, executionGate, workerGateContext);
    }

    /**
     * Worker 执行循环（不持线程；由 server 的 {@code WorkerLoopLifecycle} 调 {@code startAsync}
     * 驱动，{@code @PreDestroy} 时调 {@code stop} 优雅停机）。
     * {@code sqlaudit.worker.id} 可经环境变量覆盖（k8s 多副本时区分 worker 身份）。
     * 候选过滤用审批流引擎的 {@code canExecute}（§3.5 前置门禁，堵未过审批直接执行）。
     */
    @Bean
    public WorkerLoop workerLoop(
            TicketUnitDao ticketUnitDao, UnitExecutionEngine engine, LeaseReaper reaper,
            ApprovalFlowEngine approvalFlowEngine,
            @Value("${sqlaudit.worker.id:server-embedded}") String workerId) {
        return new WorkerLoop(ticketUnitDao, engine, reaper, workerId, approvalFlowEngine::canExecute);
    }
}
