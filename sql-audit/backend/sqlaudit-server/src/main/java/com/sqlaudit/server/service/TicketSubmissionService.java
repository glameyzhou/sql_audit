package com.sqlaudit.server.service;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RollbackPlan;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.common.model.SqlObject;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.query.gate.SchemaBoundaryChecker;
import com.sqlaudit.rollback.capability.CapabilityContext;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.rule.risk.RiskAssessor;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import com.sqlaudit.workflow.TicketService;
import com.sqlaudit.worker.gateway.JdbcTableMetadataProvider.InstanceContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交编排服务（README §4.2 提交时序）：
 * 解析（parser）→ 规则审核（rule）→ 回滚能力清单（rollback）→ 风险分级（RiskAssessor）
 * → 建单（workflow TicketService）。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>预览（{@link #preview}）与建单（{@link #submit}）走同一套服务端判定——
 *       前端预览结果仅供参考，<strong>建单时服务端重新跑全链路</strong>，不信任客户端；</li>
 *   <li>ERROR 级命中在建单入口即拒绝（README §4.3），不产生 draft 工单；</li>
 *   <li><strong>单实例工单</strong>（决定 10）：一个 {@code instanceId}，身份取登录态
 *       （creatorId 由 controller 传，不再出现在请求体）；</li>
 *   <li><strong>建单前试解析审批链</strong>（契约 §6）：非 LOW 空链 / 无可审人 →
 *       {@code APPROVAL_FLOW_NOT_CONFIGURED} / {@code NO_ELIGIBLE_APPROVER} 400，
 *       <strong>不建单</strong>；</li>
 *   <li>SQL 长度上限（契约 §4.6）：单工单 SQL 文本 ≤ 1 MiB（UTF-8 字节），超限
 *       {@code SQL_TOO_LARGE}（400）。</li>
 * </ul>
 */
@Service
public class TicketSubmissionService {

    /** 单工单 SQL 文本上限：1 MiB（UTF-8 字节，契约 §4.6）。 */
    public static final int MAX_TICKET_SQL_BYTES = 1024 * 1024;

    private final SqlParserService parser;
    private final RuleEngine ruleEngine;
    private final RollbackCapabilityAssessor capabilityAssessor;
    private final TicketService ticketService;
    private final ApprovalFlowEngine approvalEngine;
    private final ApprovalRouter approvalRouter;
    private final InstanceDao instanceDao;
    private final TableMetadataProvider tableMetadataProvider;

    public TicketSubmissionService(SqlParserService parser, RuleEngine ruleEngine,
                                   RollbackCapabilityAssessor capabilityAssessor,
                                   TicketService ticketService, ApprovalFlowEngine approvalEngine,
                                   ApprovalRouter approvalRouter, InstanceDao instanceDao,
                                   TableMetadataProvider tableMetadataProvider) {
        this.parser = parser;
        this.ruleEngine = ruleEngine;
        this.capabilityAssessor = capabilityAssessor;
        this.ticketService = ticketService;
        this.approvalEngine = approvalEngine;
        this.approvalRouter = approvalRouter;
        this.instanceDao = instanceDao;
        this.tableMetadataProvider = tableMetadataProvider;
    }

    /** 一个 unit 的预览视图（前端提交页展示；statementType 按存储码序列化）。 */
    public record UnitPreview(
            int unitIndex,
            String statement,
            StatementType statementType,
            boolean readOnly,
            List<String> objects,
            List<RuleHitView> ruleHits,
            RollbackPlanView rollbackPlan) {
    }

    public record RuleHitView(String code, String severity, String message) {
        static RuleHitView from(RuleHit h) {
            return new RuleHitView(h.getCode().getCode(), h.getSeverity().name(), h.getMessage());
        }
    }

    /** 回滚能力预判视图（coverage / generator 按存储码序列化）。 */
    public record RollbackPlanView(RollbackCoverage coverage, RollbackGenerator generator,
                                   String unavailableReason, String note) {
        static RollbackPlanView from(RollbackPlan p) {
            return new RollbackPlanView(p.getCoverage(), p.getGenerator(),
                    p.getUnavailableReason() == null ? null : p.getUnavailableReason().name(),
                    p.getReverseStatementPreview().isEmpty() ? null : p.getReverseStatementPreview().get(0));
        }
    }

    /** 预览结果（dialect 为存储码字符串；riskLevel 按存储码序列化）。 */
    public record PreviewResult(
            String dialect,
            int unitCount,
            RiskLevel riskLevel,
            boolean errorBlocked,
            List<UnitPreview> units,
            String rollbackSummary) {
    }

    /** 提交预览（无副作用；单实例，instanceId 用于元数据路由）。 */
    public PreviewResult preview(String sql, Dialect dialect, long instanceId) {
        requireSqlSize(sql, MAX_TICKET_SQL_BYTES);
        InstanceContext.set(instanceId <= 0 ? null : instanceId);
        try {
            return doPreview(sql, dialect);
        } finally {
            InstanceContext.set(null);
        }
    }

    /**
     * 建单（含提交审核 draft → submitted → auditing，并立即生成审批节点）。
     *
     * <p>两步提交已合并（影响面评估同步完成）：建单成功后审批节点即存在——
     * LOW 空链直接 approved，其余进入审批。</p>
     *
     * @throws SqlauditException ERROR_RULE_BLOCKED / SQL_TOO_LARGE /
     *                           APPROVAL_FLOW_NOT_CONFIGURED / NO_ELIGIBLE_APPROVER（不建单）
     */
    public long submit(String title, long creatorId, long instanceId,
                       String sql, String shardConfig, Dialect dialect) {
        requireSqlSize(sql, MAX_TICKET_SQL_BYTES);
        InstanceContext.set(instanceId <= 0 ? null : instanceId);
        try {
            return doSubmit(title, creatorId, instanceId, sql, shardConfig, dialect);
        } finally {
            InstanceContext.set(null);
        }
    }

    private long doSubmit(String title, long creatorId, long instanceId,
                          String sql, String shardConfig, Dialect dialect) {
        EvaluatedPreview eval = evaluatePreview(sql, dialect);
        PreviewResult preview = eval.preview();
        if (eval.parsed().isEmpty()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "SQL 不能为空");
        }

        // ERROR 阻断在入口拒绝（README §4.3）——不产生 draft，前端拿到 400 + 命中明细
        if (preview.errorBlocked()) {
            throw new SqlauditException(ErrorCode.ERROR_RULE_BLOCKED,
                    "存在 ERROR 级规则命中，禁止提交（预览详情见 ruleHits）");
        }

        List<TicketUnit> units = new ArrayList<>();
        for (ParsedStatement ps : eval.parsed()) {
            units.add(toUnit(ps, instanceId, eval.dialect(), eval.defaultSchema()));
        }

        // 建单前试解析审批链（契约 §6：失败 400 且不建单）——传入未持久化的工单即可
        Ticket draft = new Ticket();
        draft.setRiskLevel(preview.riskLevel());
        draft.setInstanceId(instanceId);
        draft.setCreatorId(creatorId);
        ApprovalChain chain = approvalRouter.route(preview.riskLevel(), instanceId);
        approvalEngine.resolveNodes(draft, chain);

        var ticket = ticketService.createTicket(title, creatorId, instanceId,
                preview.riskLevel(), preview.rollbackSummary(), sql, shardConfig, units);

        ticketService.submitForAudit(ticket.getId(), eval.outcome());
        // 提交即生成审批节点（两步合并）：LOW 空链直接 approved，其余进入审批。
        // 试解析（上方 resolveNodes）已保证非 LOW 空链 / 无可审人不会走到这里。
        ticketService.submitForApproval(ticket.getId());
        return ticket.getId();
    }

    private void requireSqlSize(String sql, int maxBytes) {
        if (sql != null && sql.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new SqlauditException(ErrorCode.SQL_TOO_LARGE,
                    "SQL 文本过长（上限 " + maxBytes + " 字节）");
        }
    }

    // ------------------------------------------------------------------

    private PreviewResult doPreview(String sql, Dialect dialect) {
        return evaluatePreview(sql, dialect).preview();
    }

    private EvaluatedPreview evaluatePreview(String sql, Dialect requestedDialect) {
        Long instanceId = InstanceContext.get();
        Instance inst = (instanceId != null && instanceId > 0) ? instanceDao.findById(instanceId) : null;
        String defaultSchema = inst == null ? null : inst.getDefaultSchema();
        Dialect effectiveDialect = requestedDialect != null
                ? requestedDialect
                : (inst != null && inst.getDialect() != null ? inst.getDialect() : Dialect.MYSQL);

        List<ParsedStatement> parsed = parser.parse(sql, effectiveDialect);

        // 实例默认库边界（平台配置哪个库就只动哪个库）：MySQL 下限定名引用其他库 → 400 拒绝。
        // 预览与提交共用本方法，一处拦截两处生效。instanceId 经 InstanceContext（ThreadLocal）注入。
        if (inst != null) {
            for (ParsedStatement ps : parsed) {
                List<String> violations = SchemaBoundaryChecker.violations(effectiveDialect, defaultSchema, ps);
                if (!violations.isEmpty()) {
                    throw new SqlauditException(ErrorCode.SCHEMA_OUT_OF_BOUND,
                            String.join("；", violations));
                }
            }
        }

        AuditOutcome outcome = ruleEngine.audit(parsed);

        List<UnitPreview> unitViews = new ArrayList<>();
        List<Map<String, Object>> summaryEntries = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedStatement ps = parsed.get(i);
            List<RuleHit> hits = outcome.hitsOf(i);
            RollbackPlan plan = capabilityAssessor.assess(
                    new CapabilityContext(ps, hits, tableMetadataProvider));

            unitViews.add(new UnitPreview(
                    i + 1,
                    ps.getOriginalSql(),
                    ps.getStatementType(),
                    ps.isReadOnly(),
                    ps.getObjects().stream().map(SqlObject::toString).toList(),
                    hits.stream().map(RuleHitView::from).toList(),
                    RollbackPlanView.from(plan)));

            // rollback_summary 是 JSON 文本列：结构化可回滚清单，枚举显式转存储码。
            // previewSql = 提交时推导的反向语句模板（不含前镜像数据，可明文落库），
            // 供工单详情在「无实际回滚主记录」时预览回滚方向；实际回滚语句以前镜像为准。
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("unit", i + 1);
            entry.put("coverage", CodedEnum.code(plan.getCoverage()));
            entry.put("generator", CodedEnum.code(plan.getGenerator()));
            if (plan.getUnavailableReason() != null) {
                entry.put("unavailableReason", plan.getUnavailableReason().name());
            }
            entry.put("previewSql", plan.getReverseStatementPreview());
            summaryEntries.add(entry);
        }

        StatementType main = dominantStatementType(parsed);
        RiskLevel risk = RiskAssessor.assess(outcome.allHits(), main, false);

        PreviewResult preview = new PreviewResult(effectiveDialect.name(), parsed.size(), risk,
                outcome.errorBlocked(), unitViews, toJson(summaryEntries));
        return new EvaluatedPreview(preview, parsed, outcome, effectiveDialect, defaultSchema);
    }

    /** 多语句工单聚合主语句类型（DDL > DML > 首条语句），避免首条低危语句掩盖后续变更。 */
    private static StatementType dominantStatementType(List<ParsedStatement> parsed) {
        if (parsed.isEmpty()) {
            return StatementType.OTHER;
        }
        StatementType dml = null;
        for (ParsedStatement ps : parsed) {
            StatementType t = ps.getStatementType();
            if (t == StatementType.DDL) {
                return StatementType.DDL;
            }
            if (dml == null && (t == StatementType.UPDATE || t == StatementType.DELETE || t == StatementType.INSERT)) {
                dml = t;
            }
        }
        return dml != null ? dml : parsed.get(0).getStatementType();
    }

    private record EvaluatedPreview(PreviewResult preview, List<ParsedStatement> parsed,
                                    AuditOutcome outcome, Dialect dialect, String defaultSchema) {
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            // 回滚清单是可读信息，序列化失败不应阻断提交；降级为空数组
            return "[]";
        }
    }

    /** ParsedStatement → TicketUnit（statement 明文；未限定库名时回落实例 defaultSchema 防止绕过结构漂移门禁）。 */
    private static TicketUnit toUnit(ParsedStatement ps, long instanceId, Dialect dialect, String defaultSchema) {
        var unit = new TicketUnit();
        unit.setInstanceId(instanceId);
        unit.setStatement(ps.getOriginalSql());
        unit.setStatementHash(sha256(ps.getOriginalSql()));
        unit.setStatementType(ps.getStatementType());
        if (!ps.getObjects().isEmpty()) {
            SqlObject obj = ps.getObjects().get(0);
            unit.setObjectName(obj.getName());
            String schema = obj.getSchema();
            if ((schema == null || schema.isBlank()) && dialect != Dialect.IOTDB) {
                schema = defaultSchema;
            }
            unit.setSchemaName(schema);
        }
        return unit;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Formatter f = new Formatter()) {
                for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                    f.format("%02x", b);
                }
                return f.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
