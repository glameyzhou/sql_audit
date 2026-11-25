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
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.worker.gateway.WorkerGateContext;
import com.sqlaudit.worker.rollback.RollbackPersisterImpl;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import com.sqlaudit.workflow.TicketStateMachine;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Worker 生产装配：注册 {@link JdbcProductionGateway}（生产库真执行网关）、执行前门禁
 * （README §4.2：{@link ExecutionGate} + 指纹提供者 + 门禁上下文）与执行循环所需的编排 bean
 * （网关桥 / 反向 SQL 落库 / 租约回收 / 执行引擎 / {@link WorkerLoop}）。
 *
 * <p><strong>worker 已并入 server 单进程</strong>：本配置不再由独立 worker 进程加载，
 * 而是由 server 的 {@code WorkerIntegrationConfig} 通过 {@code @Import} 引入——
 * {@code SPRING_PROFILES_ACTIVE=prod,postgres}（或 {@code prod} profile group）时，
 * 一个进程同时跑 REST API + worker 执行循环。生产无需再单独部署 worker 进程。</p>
 *
 * <p><strong>装配条件</strong>：平台库只剩 mysql / postgres 两个真库方言，
 * 本类不再按 profile 条件装配、恒注册；执行循环是否启动由开关
 * {@code sqlaudit.worker.enabled} 控制（见 server 的 {@code WorkerLoopLifecycle}）。</p>
 *
 * <p>依赖宿主（server 各 profile 装配）提供：{@code TicketUnitDao}/{@code ExecLockDao}/
 * {@code RollbackDao}/{@code InstanceDao}/{@code ApprovalDao}/{@code TicketDao}/
 * {@code CredentialDao}（mysql/postgres 各自的 JDBC DAO）、平台库 {@code JdbcTemplate}
 * （Spring Boot 自动配置）、{@code SqlParserService}/{@code TableMetadataProvider}/
 * {@code RuleEngine}/{@code RollbackCapabilityAssessor}/{@code ApprovalFlowEngine}
 * （server 公共装配）。本类只声明 worker 侧组合关系，不负责平台库连接本身。</p>
 */
@Configuration(proxyBeanMethods = false)
public class WorkerConfig {

    /**
     * 生产库真执行网关（README §3.1：worker 是唯一连生产库的进程，server 内嵌后仍是本 bean 独享）。
     *
     * <p><strong>bean 名是 {@code workerProductionGateway}</strong>（方法名即 bean 名）：
     * server 侧 {@code ServerBeansConfig} 另有一个同名语义的 {@code productionGateway}
     * （回滚执行网关，无前镜像路径）。两者都实现
     * {@link com.sqlaudit.executor.gateway.ProductionGateway} 接口、在
     * mysql/postgres profile 下共存——Spring Boot 3 默认禁止 bean 定义覆盖，同名会直接
     * 启动失败，故必须区分；注入按各自具体类解析，不按接口。</p>
     */
    @Bean
    public JdbcProductionGateway workerProductionGateway(InstanceDao instanceDao, JdbcTemplate jdbcTemplate) {
        return new JdbcProductionGateway(instanceDao, jdbcTemplate);
    }

    /** 执行桥：unit 明文 → parser 解析 → 现场构造前镜像 → 交网关单事务执行。 */
    @Bean
    public UnitExecutionEngine.ProductionGatewayBridge gatewayBridge(
            JdbcProductionGateway productionGateway, SqlParserService parser,
            TableMetadataProvider metadata, InstanceDao instanceDao) {
        return new GatewayBridgeImpl(productionGateway, parser, metadata, instanceDao);
    }

    /** 反向 SQL 生命周期服务（生成 → 语法自检 → verified，README §4.4）。 */
    @Bean
    public RollbackLifecycleService rollbackLifecycleService(RollbackDao rollbackDao, SqlParserService parser) {
        return new RollbackLifecycleService(rollbackDao, parser);
    }

    /** 反向 SQL 落库桥（执行成功后用前镜像生成反向语句持久化，README §4.4 步骤 4）。 */
    @Bean
    public UnitExecutionEngine.RollbackPersister rollbackPersister(
            RollbackLifecycleService lifecycle, SqlParserService parser,
            TableMetadataProvider metadata, InstanceDao instanceDao) {
        return new RollbackPersisterImpl(lifecycle, parser, metadata, instanceDao);
    }

    /** 租约回收器（reap：安全回 pending / 转 needs_confirm / 清过期锁，README §4.2）。 */
    @Bean
    public LeaseReaper leaseReaper(TicketUnitDao ticketUnitDao, ExecLockDao execLockDao) {
        return new LeaseReaper(ticketUnitDao, execLockDao);
    }

    /** 结构指纹提供者（README §4.2 结构漂移门禁：RO_META 连库重算 SHOW CREATE / pg_get_tabledef）。 */
    @Bean
    public JdbcStructureFingerprintProvider structureFingerprintProvider(
            InstanceDao instanceDao, CredentialDao credentialDao) {
        return new JdbcStructureFingerprintProvider(instanceDao, credentialDao);
    }

    /** 生产库表元数据真实现（RO_META + information_schema），供规则引擎 / 前镜像 /
     *  回滚复核取真实表结构。 */
    @Bean
    public JdbcTableMetadataProvider tableMetadataProvider(
            InstanceDao instanceDao, CredentialDao credentialDao) {
        return new JdbcTableMetadataProvider(instanceDao, credentialDao);
    }

    /** 执行前门禁（README §4.2：审批时效 / 结构漂移 / 回滚能力复核，任一不过不执行）。 */
    @Bean
    public ExecutionGate executionGate(JdbcStructureFingerprintProvider structureFingerprintProvider) {
        return new ExecutionGate(structureFingerprintProvider);
    }

    /** 门禁上下文（README §4.2 worker 侧装配：审批时间 + 回滚能力复核 + 超时置 expired 补偿）。 */
    @Bean
    public WorkerGateContext workerGateContext(
            ApprovalDao approvalDao, TicketDao ticketDao, InstanceDao instanceDao,
            SqlParserService parser, RuleEngine ruleEngine,
            RollbackCapabilityAssessor rollbackCapabilityAssessor, TableMetadataProvider metadata) {
        return new WorkerGateContext(approvalDao, ticketDao, instanceDao, parser,
                ruleEngine, rollbackCapabilityAssessor, metadata);
    }

    /** 执行单元引擎（抢占 → 实例锁 → 门禁 → 预写 intent → 执行 → 幂等确认，README §4.2 协议核心）。 */
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
     * 候选过滤用审批流引擎的 {@code canExecute}（README §4.2 前置门禁，堵未过审批直接执行）。
     */
    @Bean
    public WorkerLoop workerLoop(
            TicketUnitDao ticketUnitDao, TicketDao ticketDao, TicketStateMachine stateMachine,
            UnitExecutionEngine engine, LeaseReaper reaper,
            ApprovalFlowEngine approvalFlowEngine,
            @Value("${sqlaudit.worker.id:server-embedded}") String workerId) {
        return new WorkerLoop(ticketUnitDao, engine, reaper, workerId,
                approvalFlowEngine::canExecute,
                ticketId -> syncTicketStatus(ticketId, ticketDao, ticketUnitDao, stateMachine, workerId));
    }

    /**
     * 根据工单下全部执行单元的状态聚合推进工单主状态：
     * {@code approved → executing → (succeeded | partially_succeeded | failed | needs_confirm)}。
     */
    private static void syncTicketStatus(long ticketId, TicketDao ticketDao, TicketUnitDao unitDao,
                                         TicketStateMachine stateMachine, String workerId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            return;
        }
        TicketStatus current = ticket.getStatus();
        if (current != TicketStatus.APPROVED && current != TicketStatus.SCHEDULED
                && current != TicketStatus.EXECUTING) {
            return;
        }
        List<TicketUnit> units = unitDao.listByTicket(ticketId);
        if (units.isEmpty()) {
            return;
        }
        // 先从 approved/scheduled 推进到 executing，再判定是否已全部收敛到终态
        if (current == TicketStatus.APPROVED || current == TicketStatus.SCHEDULED) {
            stateMachine.transition(ticket, TicketStatus.EXECUTING, "worker:" + workerId);
        }
        boolean hasPending = false;
        boolean hasNeedsConfirm = false;
        int successCount = 0;
        int failedCount = 0;
        for (TicketUnit u : units) {
            UnitStatus s = u.getStatus();
            if (s == UnitStatus.PENDING || s == UnitStatus.LEASED || s == UnitStatus.EXECUTING) {
                hasPending = true;
            } else if (s == UnitStatus.NEEDS_CONFIRM) {
                hasNeedsConfirm = true;
            } else if (s == UnitStatus.SUCCESS || s == UnitStatus.SKIPPED) {
                successCount++;
            } else if (s == UnitStatus.FAILED) {
                failedCount++;
            }
        }
        if (hasPending) {
            return;
        }
        TicketStatus target;
        if (hasNeedsConfirm) {
            target = TicketStatus.NEEDS_CONFIRM;
        } else if (failedCount == 0) {
            target = TicketStatus.SUCCEEDED;
        } else if (successCount > 0) {
            target = TicketStatus.PARTIALLY_SUCCEEDED;
        } else {
            target = TicketStatus.FAILED;
        }
        stateMachine.transition(ticket, target, "worker:" + workerId);
    }
}
