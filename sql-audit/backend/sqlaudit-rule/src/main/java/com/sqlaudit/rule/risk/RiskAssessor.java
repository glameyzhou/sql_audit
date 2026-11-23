package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.RuleHit;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 风险分级（README §4.3）。
 *
 * <table>
 *   <tr><th>等级</th><th>判定</th><th>审批链</th></tr>
 *   <tr><td>LOW</td><td>只读查询 / 低危且 staging</td><td>自动通过，留痕</td></tr>
 *   <tr><td>MEDIUM</td><td>常规 DML、影响行数 &lt; 阈值</td><td>主管 1 人</td></tr>
 *   <tr><td>HIGH</td><td>高危规则、prod 大表变更、DDL</td><td>主管 + DBA</td></tr>
 *   <tr><td>CRITICAL</td><td>破坏性 DDL、不可回滚变更、跨库批量</td><td>主管 + DBA + 管理员，执行前二次确认</td></tr>
 * </table>
 *
 * <p>命中 CRITICAL 码集合（破坏性 DDL / 回滚不可用 / 前镜像超限 / 解析失败）→ CRITICAL；
 * 其余任一 ERROR → HIGH；仅 WARN → MEDIUM；无命中 → LOW。
 * 影响面未知（README §4.3）升一级。</p>
 */
public final class RiskAssessor {

    /** 直接定级 CRITICAL 的规则码（README §4.3：不可回滚与解析失败是硬底线）。 */
    public static final Set<RuleCode> CRITICAL_CODES = EnumSet.of(
            RuleCode.R_DDL_DESTRUCTIVE,
            RuleCode.R_ROLLBACK_UNAVAILABLE,
            RuleCode.R_PREIMAGE_TOO_LARGE,
            RuleCode.R_PARSE_FAILED);

    private RiskAssessor() {
    }

    /**
     * 分级。
     *
     * @param hits         全部命中（工单级汇总）
     * @param statementType 工单主语句类型（无命中时 DML → MEDIUM，其他 → LOW）
     * @param impactUnknown 影响面未知（README §4.3：升一级）
     */
    public static com.sqlaudit.common.enums.RiskLevel assess(List<RuleHit> hits,
                                                             StatementType statementType,
                                                             boolean impactUnknown) {
        com.sqlaudit.common.enums.RiskLevel level = baseLevel(hits, statementType);
        if (impactUnknown) {
            level = bump(level);
        }
        return level;
    }

    private static com.sqlaudit.common.enums.RiskLevel baseLevel(List<RuleHit> hits,
                                                                 StatementType statementType) {
        boolean hasError = false;
        for (RuleHit h : hits) {
            if (CRITICAL_CODES.contains(h.getCode())) {
                return com.sqlaudit.common.enums.RiskLevel.CRITICAL;
            }
            if (h.getSeverity() == RuleSeverity.ERROR) {
                hasError = true;
            }
        }
        if (hasError) {
            return com.sqlaudit.common.enums.RiskLevel.HIGH;
        }
        if (!hits.isEmpty()) {
            return com.sqlaudit.common.enums.RiskLevel.MEDIUM;
        }
        // 无命中：DML 常规（MEDIUM）；其他（查询/DDL 未触发规则）LOW
        return statementType == StatementType.UPDATE
                || statementType == StatementType.DELETE
                || statementType == StatementType.INSERT
                ? com.sqlaudit.common.enums.RiskLevel.MEDIUM
                : com.sqlaudit.common.enums.RiskLevel.LOW;
    }

    private static com.sqlaudit.common.enums.RiskLevel bump(com.sqlaudit.common.enums.RiskLevel level) {
        return switch (level) {
            case LOW -> com.sqlaudit.common.enums.RiskLevel.MEDIUM;
            case MEDIUM -> com.sqlaudit.common.enums.RiskLevel.HIGH;
            case HIGH, CRITICAL -> com.sqlaudit.common.enums.RiskLevel.CRITICAL;
        };
    }
}
