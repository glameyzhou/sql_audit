package com.sqlaudit.server.service;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.worker.gateway.JdbcTableMetadataProvider.InstanceContext;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RollbackPlan;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.common.model.SqlObject;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.capability.CapabilityContext;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.rule.risk.RiskAssessor;
import com.sqlaudit.workflow.TicketService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交编排服务（设计文档 §3.1 提交时序）：
 * 解析（parser）→ 规则审核（rule）→ 回滚能力清单（rollback）→ 风险分级（RiskAssessor）
 * → 建单（workflow TicketService）。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>预览（{@link #preview}）与建单（{@link #submit}）走同一套服务端判定——
 *       前端预览结果仅供参考，<strong>建单时服务端重新跑全链路</strong>，不信任客户端；</li>
 *   <li>ERROR 级命中在建单入口即拒绝（§4.3），不产生 draft 工单；</li>
 *   <li>rollbackSummary（§5.1 可回滚清单）随工单落库。</li>
 * </ul>
 */
@Service
public class TicketSubmissionService {

    private final SqlParserService parser;
    private final RuleEngine ruleEngine;
    private final RollbackCapabilityAssessor capabilityAssessor;
    private final TicketService ticketService;

    public TicketSubmissionService(SqlParserService parser, RuleEngine ruleEngine,
                                   RollbackCapabilityAssessor capabilityAssessor,
                                   TicketService ticketService) {
        this.parser = parser;
        this.ruleEngine = ruleEngine;
        this.capabilityAssessor = capabilityAssessor;
        this.ticketService = ticketService;
    }

    /** 一个 unit 的预览视图（前端提交页展示）。 */
    public record UnitPreview(
            int unitIndex,
            String statement,
            String statementType,
            boolean readOnly,
            List<String> objects,
            List<RuleHitView> ruleHits,
            RollbackPlanView rollbackPlan) {
    }

    public record RuleHitView(String code, String severity, String message) {
        static RuleHitView from(RuleHit h) {
            return new RuleHitView(h.getCode(), h.getSeverity().name(), h.getMessage());
        }
    }

    public record RollbackPlanView(String coverage, String generator, String unavailableReason,
                                   String note) {
        static RollbackPlanView from(RollbackPlan p) {
            return new RollbackPlanView(p.getCoverage().name(), p.getGenerator(),
                    p.getUnavailableReason() == null ? null : p.getUnavailableReason().name(),
                    p.getReverseStatementPreview().isEmpty() ? null : p.getReverseStatementPreview().get(0));
        }
    }

    /** 预览结果。 */
    public record PreviewResult(
            String dialect,
            int unitCount,
            RiskLevel riskLevel,
            boolean errorBlocked,
            List<UnitPreview> units,
            String rollbackSummary) {
    }

    /** 提交预览（无副作用；instanceIds 用于元数据路由，单默认实例语义）。 */
    public PreviewResult preview(String sql, Dialect dialect, List<Long> instanceIds) {
        InstanceContext.set(instanceIds == null || instanceIds.isEmpty()
                ? null : instanceIds.get(0));
        try {
            return doPreview(sql, dialect);
        } finally {
            InstanceContext.set(null);
        }
    }

    /**
     * 建单（含提交审核 draft → submitted → auditing）。
     *
     * @throws com.sqlaudit.common.exception.SqlauditException ERROR_RULE_BLOCKED（不建单）
     */
    public long submit(String title, long creatorId, List<Long> instanceIds, String environment,
                       String sql, Dialect dialect) {
        // 元数据路由上下文（单默认实例语义，见 defaultInstanceId）：
        // 预览判定与建单审计都在同一实例上下文内取真实表元数据
        InstanceContext.set(instanceIds == null || instanceIds.isEmpty()
                ? null : instanceIds.get(0));
        try {
            return doSubmit(title, creatorId, instanceIds, environment, sql, dialect);
        } finally {
            InstanceContext.set(null);
        }
    }

    private long doSubmit(String title, long creatorId, List<Long> instanceIds, String environment,
                          String sql, Dialect dialect) {
        PreviewResult preview = doPreview(sql, dialect);

        // ERROR 阻断在入口拒绝（§4.3）——不产生 draft，前端拿到 400 + 命中明细
        if (preview.errorBlocked()) {
            throw new com.sqlaudit.common.exception.SqlauditException("ERROR_RULE_BLOCKED",
                    "存在 ERROR 级规则命中，禁止提交（预览详情见 ruleHits）");
        }

        List<com.sqlaudit.storage.model.TicketUnit> units = new ArrayList<>();
        List<ParsedStatement> parsed = parser.parse(sql, dialect);
        for (int i = 0; i < parsed.size(); i++) {
            units.add(toUnit(parsed.get(i), defaultInstanceId(instanceIds)));
        }

        var ticket = ticketService.createTicket(title, creatorId, instanceIds, environment,
                preview.riskLevel(), preview.rollbackSummary(), units);

        AuditOutcome outcome = ruleEngine.audit(parsed);
        ticketService.submitForAudit(ticket.getId(), outcome);
        return ticket.getId();
    }

    // ------------------------------------------------------------------

    private PreviewResult doPreview(String sql, Dialect dialect) {
        List<ParsedStatement> parsed = parser.parse(sql, dialect);
        AuditOutcome outcome = ruleEngine.audit(parsed);

        List<UnitPreview> unitViews = new ArrayList<>();
        List<Map<String, Object>> summaryEntries = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedStatement ps = parsed.get(i);
            List<RuleHit> hits = outcome.hitsOf(i);
            RollbackPlan plan = capabilityAssessor.assess(
                    new CapabilityContext(ps, hits, null));

            unitViews.add(new UnitPreview(
                    i + 1,
                    ps.getOriginalSql(),
                    ps.getStatementType().name(),
                    ps.isReadOnly(),
                    ps.getObjects().stream().map(SqlObject::toString).toList(),
                    hits.stream().map(RuleHitView::from).toList(),
                    RollbackPlanView.from(plan)));

            // rollback_summary 是 JSON 列（§9）：结构化可回滚清单，不存自由文本
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("unit", i + 1);
            entry.put("coverage", plan.getCoverage().name());
            entry.put("generator", plan.getGenerator());
            if (plan.getUnavailableReason() != null) {
                entry.put("unavailableReason", plan.getUnavailableReason().name());
            }
            summaryEntries.add(entry);
        }

        StatementType main = parsed.isEmpty() ? StatementType.OTHER : parsed.get(0).getStatementType();
        RiskLevel risk = RiskAssessor.assess(outcome.allHits(), main, false);

        return new PreviewResult(dialect.name(), parsed.size(), risk,
                outcome.errorBlocked(), unitViews, toJson(summaryEntries));
    }

    private static long defaultInstanceId(List<Long> instanceIds) {
        // unit.instance_id 是 NOT NULL（§9）；单实例工单直接取，多实例+多语句的
        // 精确映射在 W2 后段细化（预览请求按语句声明 instanceId）
        return instanceIds == null || instanceIds.isEmpty() ? 0L : instanceIds.get(0);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            // 回滚清单是可读信息，序列化失败不应阻断提交；降级为空数组
            return "[]";
        }
    }

    /** ParsedStatement → TicketUnit（statement 明文 + hash；加密由 W2 存储层做）。 */
    private static com.sqlaudit.storage.model.TicketUnit toUnit(ParsedStatement ps, long instanceId) {
        var unit = new com.sqlaudit.storage.model.TicketUnit();
        unit.setInstanceId(instanceId);
        unit.setStatement(ps.getOriginalSql().getBytes(StandardCharsets.UTF_8));
        unit.setStatementHash(sha256(ps.getOriginalSql()));
        unit.setStatementType(ps.getStatementType().name());
        if (!ps.getObjects().isEmpty()) {
            SqlObject obj = ps.getObjects().get(0);
            unit.setObjectName(obj.getName());
            unit.setSchemaName(obj.getSchema());
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
