package com.sqlaudit.rule.rules;

import com.sqlaudit.rule.BuiltInRules;
import com.sqlaudit.rule.ImpactEstimate;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleContext;
import com.sqlaudit.rule.RuleDefinition;

import java.util.List;
import java.util.Map;

/**
 * 需要影响面评估的规则组（设计文档 §4.3 / §4.4）。
 *
 * <p>R_AFFECT_TOO_MANY / R_LOW_PEAK_WINDOW / R_PREIMAGE_TOO_LARGE。</p>
 *
 * <p>影响面评估<strong>异步</strong>（§4.4：COUNT(*) 超时 3s 装不进审核接口 P95 &lt; 500ms）：
 * 首轮同步审核 ctx.impact()=null 时本组规则全部跳过；异步评估完成后由
 * {@link com.sqlaudit.rule.engine.RuleEngine#assessImpact} 二次触发。
 * 评估未完成的工单不允许提交审批（workflow 层守卫）。</p>
 */
public final class ImpactRules {

    private ImpactRules() {
    }

    private static RuleDefinition def(String code) {
        return BuiltInRules.byCode(code);
    }

    /** R_AFFECT_TOO_MANY：COUNT(*) 超阈值（默认 10 万）→ ERROR，强制 DBA 节点（仅此职责，v2.1 拆分）。 */
    public static Rule affectTooMany() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_AFFECT_TOO_MANY");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ImpactEstimate impact = ctx.impact();
                if (impact == null || impact.estimatedRows() == null) {
                    return List.of();
                }
                long threshold = definition().longParam("threshold", 100_000L);
                if (impact.estimatedRows() > threshold) {
                    return List.of(hit("影响行数 " + impact.estimatedRows() + " 超阻断阈值 " + threshold
                            + "，强制 DBA 审批节点", Map.of("estimatedRows", impact.estimatedRows())));
                }
                return List.of();
            }
        };
    }

    /** R_LOW_PEAK_WINDOW：影响行数超「建议低峰」阈值（默认 1 千）但未达阻断 → WARN（与阻断拆分，v2.1）。 */
    public static Rule lowPeakWindow() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_LOW_PEAK_WINDOW");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ImpactEstimate impact = ctx.impact();
                if (impact == null || impact.estimatedRows() == null) {
                    return List.of();
                }
                long threshold = definition().longParam("threshold", 1_000L);
                if (impact.estimatedRows() > threshold) {
                    return List.of(hit("影响行数 " + impact.estimatedRows() + " 超低峰建议阈值 " + threshold
                            + "，建议低峰执行", Map.of("estimatedRows", impact.estimatedRows())));
                }
                return List.of();
            }
        };
    }

    /** R_PREIMAGE_TOO_LARGE：预估前镜像行数超上限（默认 5000 行）→ ERROR，转分片/binlog/快照。 */
    public static Rule preimageTooLarge() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_PREIMAGE_TOO_LARGE");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ImpactEstimate impact = ctx.impact();
                if (impact == null || impact.estimatedRows() == null) {
                    return List.of();
                }
                long rowLimit = definition().longParam("rowLimit", 5_000L);
                if (impact.estimatedRows() > rowLimit) {
                    return List.of(hit("预估前镜像行数 " + impact.estimatedRows() + " 超上限 " + rowLimit
                            + "，转主键分片 / binlog 闪回 / 快照", Map.of("estimatedRows", impact.estimatedRows())));
                }
                return List.of();
            }
        };
    }

    /** 该组全部规则。 */
    public static List<Rule> all() {
        return List.of(affectTooMany(), lowPeakWindow(), preimageTooLarge());
    }
}
