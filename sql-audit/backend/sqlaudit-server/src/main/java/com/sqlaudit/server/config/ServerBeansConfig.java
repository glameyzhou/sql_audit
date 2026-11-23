package com.sqlaudit.server.config;

import com.sqlaudit.auth.AesGcmDetailCipher;
import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.audit.ChainVerifier;
import com.sqlaudit.audit.DetailCipher;
import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.query.channel.ConnectionProvider;
import com.sqlaudit.query.channel.QueryChannel;
import com.sqlaudit.query.channel.QueryOptions;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.RuleConfig;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.server.gateway.JdbcProductionGateway;
import com.sqlaudit.server.service.RollbackExecutor;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import com.sqlaudit.workflow.TicketService;
import com.sqlaudit.workflow.TicketStateMachine;
import com.sqlaudit.workflow.TicketUnitWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * 公共装配配置：解析 / 规则 / 回滚 / 审计 / 工单审批流 / 查询通道 / SQL 密文密钥。
 *
 * <p>平台库只剩 mysql / postgres 两个真库方言，本类恒装配（不再有 memory 默认 profile）：
 * 表元数据 / 结构指纹由 worker 侧真实现（{@code WorkerConfig#tableMetadataProvider} /
 * {@code WorkerConfig#structureFingerprintProvider}）提供，不再有 stub 分支。
 * 未激活任何方言 profile 时，DataSource 缺失导致启动失败（没有默认方言）。</p>
 */
@Configuration
public class ServerBeansConfig {

    // ---------------- 解析 / 规则 / 回滚 ----------------

    @Bean
    public SqlParserService sqlParserService() {
        return new SqlParserService();
    }

    @Bean
    public RuleEngine ruleEngine(TableMetadataProvider metadata, RuleConfig ruleConfig) {
        return new RuleEngine(metadata, ruleConfig);
    }

    @Bean
    public RollbackCapabilityAssessor rollbackCapabilityAssessor() {
        return new RollbackCapabilityAssessor();
    }

    // ---------------- SQL 密文密钥（契约 §0 / 阶段 A §3） ----------------

    /**
     * 工单 / 执行语句 / 回滚反向语句的存储层透明加解密器（AES-GCM）。
     * 密钥走环境变量 {@code SQLAUDIT_SQL_KEY}（缺省本地开发密钥；生产必须注入——
     * 密钥丢失则历史工单与回滚 SQL 不可读）。JDBC 的 Ticket / TicketUnit / Rollback
     * DAO 构造时注入（见 MysqlProfileConfig / PostgresProfileConfig）。
     */
    @Bean
    public SqlCipher sqlCipher() {
        return new SqlCipher();
    }

    // ---------------- 回滚执行 ----------------

    /**
     * 回滚执行网关（回滚执行经此路由到实例 RW_EXEC 连接池，无前镜像路径）。
     *
     * <p>与 worker 侧 {@code WorkerConfig#workerProductionGateway}（unit 执行网关，前镜像
     * 协议）共存——两者都实现 {@link ProductionGateway}，注入一律按具体类解析，不按接口
     * （Spring Boot 3 默认禁止 bean 定义覆盖，同名会直接启动失败，故 bean 名必须区分）。</p>
     */
    @Bean
    public JdbcProductionGateway productionGateway(InstanceDao instanceDao, CredentialDao credentialDao) {
        return new JdbcProductionGateway(instanceDao, credentialDao);
    }

    @Bean
    public RollbackExecutor rollbackExecutor(RollbackDao rollbackDao, TicketUnitDao ticketUnitDao,
                                             JdbcProductionGateway productionGateway,
                                             AuditWriter auditWriter, ZoneId zone) {
        return new RollbackExecutor(rollbackDao, ticketUnitDao, productionGateway, auditWriter, zone);
    }

    // ---------------- 审计（README §4.1 hash 链） ----------------

    @Bean
    public ZoneId platformZone() {
        return ZoneId.of("Asia/Shanghai");
    }

    /**
     * 审计明细加密器（README §4.1：detail 密文落库、哈希算明文）。
     * AES-GCM 实现属 auth 模块；密钥经 {@code SQLAUDIT_DETAIL_KEY} 注入，缺省用本地开发密钥。
     */
    @Bean
    public DetailCipher detailCipher() {
        return new AesGcmDetailCipher();
    }

    @Bean
    public AuditWriter auditWriter(AuditDao auditDao, DetailCipher detailCipher, ZoneId zone) {
        return new AuditWriter(auditDao, detailCipher, zone);
    }

    @Bean
    public ChainVerifier chainVerifier(AuditDao auditDao, DetailCipher detailCipher, ZoneId zone) {
        return new ChainVerifier(auditDao, detailCipher, zone);
    }

    // ---------------- 工单与审批流 ----------------

    @Bean
    public TicketStateMachine ticketStateMachine(TicketDao ticketDao, AuditWriter auditWriter, ZoneId zone) {
        // 状态迁移全量进审计链（README §4.1 事件表：state_change）
        return new TicketStateMachine(ticketDao, (ticketId, from, to, actor) -> {
            com.sqlaudit.common.model.AuditEvent event = com.sqlaudit.common.model.AuditEvent.builder()
                    .actorId(parseActor(actor))
                    .actorName(actor)
                    .actorRole(com.sqlaudit.common.model.AuditEvent.SYSTEM_ACTOR_ROLE)
                    .action(AuditAction.TICKET_STATE_CHANGE)
                    .targetType(AuditTargetType.TICKET)
                    .targetId(String.valueOf(ticketId))
                    // from / to 为 TicketStatus 枚举，按 toString（常量名）写入，与此前一致
                    .detailPlaintext("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"actor\":\"" + actor + "\"}")
                    .result(AuditResult.SUCCESS)
                    .traceId("ticket-" + ticketId)
                    .createdAt(java.time.OffsetDateTime.now(zone))
                    .build();
            auditWriter.append(event);
        });
    }

    private static long parseActor(String actor) {
        try {
            return Long.parseLong(actor);
        } catch (NumberFormatException e) {
            return 0; // SYSTEM / approval-ttl-checker 等非人类 actor
        }
    }

    @Bean
    public ApprovalFlowEngine approvalFlowEngine(ApprovalDao approvalDao, TicketDao ticketDao,
                                                 UserDao userDao, RoleDao roleDao, InstanceDao instanceDao,
                                                 TicketStateMachine stateMachine) {
        return new ApprovalFlowEngine(approvalDao, ticketDao, userDao, roleDao, instanceDao, stateMachine);
    }

    @Bean
    public FlowConfig flowConfig() {
        return new FlowConfig();
    }

    @Bean
    public ApprovalRouter approvalRouter(FlowConfig flowConfig) {
        return new ApprovalRouter(flowConfig);
    }

    @Bean
    public TicketService ticketService(TicketDao ticketDao, TicketUnitDao ticketUnitDao,
                                       TicketStateMachine stateMachine, ApprovalFlowEngine approvalEngine,
                                       ApprovalRouter approvalRouter) {
        TicketUnitWriter unitWriter = ticketUnitDao::insert;
        return new TicketService(ticketDao, unitWriter, stateMachine, approvalEngine, approvalRouter);
    }

    // ---------------- 查询通道（按实例路由的只读连接） ----------------

    /**
     * 查询连接提供者：按 instanceId 连目标实例的真实库，优先 RO_DATA、回落 RO_META
     * （见 {@link com.sqlaudit.server.service.JdbcQueryConnectionProvider}）。
     */
    @Bean
    public ConnectionProvider connectionProvider(InstanceDao instanceDao, CredentialDao credentialDao) {
        return new com.sqlaudit.server.service.JdbcQueryConnectionProvider(instanceDao, credentialDao);
    }

    @Bean
    public QueryChannel queryChannel(ConnectionProvider connectionProvider) {
        return new QueryChannel(connectionProvider, new QueryOptions(1000, 10));
    }
}
