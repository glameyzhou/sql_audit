package com.sqlaudit.rule.engine;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.ImpactEstimate;
import com.sqlaudit.rule.ImpactEstimator;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleContext;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.rules.AstDdlRules;
import com.sqlaudit.rule.rules.AstDmlRules;
import com.sqlaudit.rule.rules.AstOtherRules;
import com.sqlaudit.rule.rules.ImpactRules;
import com.sqlaudit.rule.rules.MetadataRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎（设计文档 §4.3 / §4.4）。
 *
 * <p>两阶段编排：</p>
 * <ol>
 *   <li>{@link #audit(List)}：同步首轮——AST 规则 + 元数据规则（影响面规则跳过，
 *       §4.4 影响面评估异步）；</li>
 *   <li>{@link #assessImpact(ParsedStatement, List)}：异步评估完成后二次触发
 *       影响面规则组（R_AFFECT_TOO_MANY / R_LOW_PEAK_WINDOW / R_PREIMAGE_TOO_LARGE）。
 *       评估结果为 unknown → 由 RiskAssessor 升一级，不在这里拦。</li>
 * </ol>
 *
 * <p>引擎不实现的两条规则（有意为之）：</p>
 * <ul>
 *   <li>R_ROLLBACK_UNAVAILABLE：由 rollback 模块在提交预览时产生（§5.1 能力边界）；</li>
 *   <li>R_SCHEMA_DRIFT：执行期规则（§3.5），提交期不触发。</li>
 * </ul>
 *
 * <p>nonOverridable 守卫：规则定义 nonOverridable=true 的 ERROR 级别不可被任何
 * 配置降级（§4.5），本引擎的规则实例 severity 恒取内置定义，配置覆盖入口在 W2
 * 的 rule 表读写层实现时再接，且必须拒绝降级 nonOverridable。</p>
 */
public class RuleEngine {

    private final TableMetadataProvider metadata;
    private final List<Rule> syncRules;

    public RuleEngine(TableMetadataProvider metadata) {
        this.metadata = metadata;
        this.syncRules = buildSyncRules();
    }

    private static List<Rule> buildSyncRules() {
        List<Rule> rules = new ArrayList<>();
        rules.addAll(AstDmlRules.all());
        rules.addAll(AstDdlRules.all());
        rules.addAll(AstOtherRules.all());
        rules.addAll(MetadataRules.all());
        return List.copyOf(rules);
    }

    /**
     * 同步首轮审核（一个工单的全部 unit）。
     *
     * @param ticketUnits 工单的全部语句（一条语句 = 一个 unit）
     */
    public AuditOutcome audit(List<ParsedStatement> ticketUnits) {
        Map<Integer, List<RuleHit>> unitHits = new LinkedHashMap<>();
        boolean errorBlocked = false;
        RuleSeverity maxSeverity = null;

        for (int i = 0; i < ticketUnits.size(); i++) {
            ParsedStatement unit = ticketUnits.get(i);
            List<RuleHit> hits = new ArrayList<>();
            RuleContext ctx = new RuleContext(unit, metadata, null, ticketUnits);
            for (Rule rule : syncRules) {
                if (!rule.definition().matchesDialect(unit.getDialect())
                        || !rule.definition().matchesStatementType(unit.getStatementType())) {
                    continue;
                }
                hits.addAll(rule.check(ctx));
            }
            unitHits.put(i, List.copyOf(hits));
            for (RuleHit h : hits) {
                if (h.getSeverity() == RuleSeverity.ERROR) {
                    errorBlocked = true;
                }
                if (maxSeverity == null || h.getSeverity() == RuleSeverity.ERROR) {
                    maxSeverity = h.getSeverity();
                }
            }
        }
        return new AuditOutcome(Map.copyOf(unitHits), errorBlocked, maxSeverity);
    }

    /**
     * 异步影响面评估完成后的二次规则判定（影响面规则组）。
     *
     * @param unit       语句
     * @param ticketUnits 工单全部 unit（工单级规则上下文）
     * @param estimate   影响面评估结果（null 视为未完成，返回空）
     */
    public List<RuleHit> assessImpact(ParsedStatement unit,
                                      List<ParsedStatement> ticketUnits,
                                      ImpactEstimate estimate) {
        if (estimate == null) {
            return List.of();
        }
        RuleContext ctx = new RuleContext(unit, metadata, estimate, ticketUnits);
        List<RuleHit> hits = new ArrayList<>();
        for (Rule rule : ImpactRules.all()) {
            if (!rule.definition().matchesDialect(unit.getDialect())
                    || !rule.definition().matchesStatementType(unit.getStatementType())) {
                continue;
            }
            hits.addAll(rule.check(ctx));
        }
        return hits;
    }

    /**
     * 便捷入口：估算并二次评估（测试与 W2 装配用；生产走异步任务）。
     */
    public List<RuleHit> assessImpact(ParsedStatement unit,
                                      List<ParsedStatement> ticketUnits,
                                      ImpactEstimator estimator) {
        ImpactEstimate estimate = estimator.estimate(unit);
        return assessImpact(unit, ticketUnits, estimate);
    }

    /** 引擎加载的同步规则数（观测用）。 */
    public int syncRuleCount() {
        return syncRules.size();
    }
}
