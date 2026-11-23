package com.sqlaudit.server.config;

import com.sqlaudit.auth.AesGcmDetailCipher;
import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.audit.ChainVerifier;
import com.sqlaudit.audit.DetailCipher;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rule.RuleConfig;
import com.sqlaudit.server.gateway.JdbcProductionGateway;
import com.sqlaudit.server.service.RollbackExecutor;
import com.sqlaudit.query.channel.ConnectionProvider;
import com.sqlaudit.query.channel.QueryChannel;
import com.sqlaudit.query.channel.QueryOptions;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryAuditDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import com.sqlaudit.workflow.TicketService;
import com.sqlaudit.workflow.TicketStateMachine;
import com.sqlaudit.workflow.TicketUnitWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.ZoneId;
import java.util.List;

/**
 * W2 公共装配配置：解析 / 规则 / 回滚 / 审计 / 工单审批流 / 查询通道——
 * 这些业务 bean 在 memory 与 mysql 两个 profile 下完全一致。
 *
 * <p>存储 DAO 按 profile 分离：memory（默认）见 {@link MemoryStorageConfig}，
 * mysql 见 {@link MysqlProfileConfig}。业务层依赖 DAO 接口，存储切换零感知。</p>
 */
@Configuration
public class ServerBeansConfig {

    // ---------------- 解析 / 规则 / 回滚 ----------------

    @Bean
    public SqlParserService sqlParserService() {
        return new SqlParserService();
    }

    /**
     * 元数据 dev stub：任何表都视为「有主键 id、在白名单、无生成列/掩码列」。
     * <p><strong>仅 memory（默认）profile 联调用</strong>——mysql/postgres/h2 profile
     * 下由 {@code WorkerConfig#tableMetadataProvider}（RO_META + information_schema
     * 真实现）取代；stub 会让 R_NO_PK / R_OBJECT_NOT_ALLOWED 恒不命中，
     * 不能用于真实审核。</p>
     */
    @Bean
    @Profile("!mysql & !postgres & !h2")
    public TableMetadataProvider tableMetadataProvider() {
        return new TableMetadataProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TableMetadata getTable(String schema, String name) {
                if (name == null) {
                    return null;
                }
                return new TableMetadata(true, List.of("id"), java.util.Set.of(),
                        List.of("id", "a", "b"), java.util.Set.of(), true);
            }
        };
    }

    @Bean
    public RuleEngine ruleEngine(TableMetadataProvider metadata, RuleConfig ruleConfig) {
        return new RuleEngine(metadata, ruleConfig);
    }

    @Bean
    public RollbackCapabilityAssessor rollbackCapabilityAssessor() {
        return new RollbackCapabilityAssessor();
    }

    // ---------------- 回滚执行 ----------------

    /**
     * 回滚执行网关（回滚执行经此路由到实例 RW_EXEC 连接池，无前镜像路径）。
     *
     * <p>与 worker 侧 {@code WorkerConfig#workerProductionGateway}（unit 执行网关，前镜像
     * 协议）在 mysql/postgres/h2 profile 下共存——两者都实现 {@link ProductionGateway}，
     * 注入一律按具体类解析（见本方法与 {@code WorkerConfig#gatewayBridge}），不按接口。</p>
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

    // ---------------- 审计（§7 hash 链） ----------------

    @Bean
    public ZoneId platformZone() {
        return ZoneId.of("Asia/Shanghai");
    }

    /**
     * 审计明细加密器（§7.3：detail 密文落库、哈希算明文）。
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
        // 状态迁移全量进审计链（§7.2 事件表：state_change）
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
                                                  TicketStateMachine stateMachine) {
        return new ApprovalFlowEngine(approvalDao, ticketDao, stateMachine);
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
