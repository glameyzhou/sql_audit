package com.sqlaudit.rule.engine;

import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RuleHit;

import java.util.List;
import java.util.Map;

/**
 * 审核结果（一轮规则引擎的输出）。
 *
 * @param unitHits    每个 unit 的命中（key = unit 下标，与输入列表一一对应）
 * @param errorBlocked 是否存在 ERROR 级命中（阻断提交）
 * @param maxSeverity 全部命中的最严级别（无命中为 null）
 */
public record AuditOutcome(
        Map<Integer, List<RuleHit>> unitHits,
        boolean errorBlocked,
        com.sqlaudit.common.enums.RuleSeverity maxSeverity) {

    /** 空 unit 的命中列表（不可变）。 */
    public List<RuleHit> hitsOf(int unitIndex) {
        return unitHits.getOrDefault(unitIndex, List.of());
    }

    /** 收集全部命中。 */
    public List<RuleHit> allHits() {
        return unitHits.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /** 指定 unit 的语句（用于输出关联；见 {@link RuleEngine#audit}）。 */
    public ParsedStatement statementOf(List<ParsedStatement> units, int unitIndex) {
        return units.get(unitIndex);
    }
}
