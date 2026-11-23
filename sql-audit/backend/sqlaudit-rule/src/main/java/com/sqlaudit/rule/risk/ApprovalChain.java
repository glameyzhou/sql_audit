package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审批链（设计文档 §4.5 / §8.2）。
 *
 * <p>节点为角色 code 序列（内置角色 + 自定义角色，如 [LEAD, DBA]）。强度比较用角色权重
 * （t_role.rank，见 {@code FlowConfig#roleRanks()}），ADMIN &gt; DBA &gt; LEAD &gt; 其他。</p>
 *
 * @param nodes           节点角色 code 序列（有序：按审批顺序）
 * @param secondConfirm   是否需要执行前二次确认（CRITICAL 专属，§3.4）
 * @param source          生效来源：L1_GLOBAL / L2_INSTANCE / L3_FALLBACK
 * @param coversRiskLevel 本链覆盖的风险等级
 */
public record ApprovalChain(
        List<String> nodes,
        boolean secondConfirm,
        String source,
        RiskLevel coversRiskLevel) {

    public ApprovalChain {
        nodes = List.copyOf(nodes);
    }

    /** 链强度（用于「取最严」与「只可加严」判定）：节点数 + 最高角色权重×10。 */
    public int strictness(Map<String, Integer> roleRank) {
        int maxRank = nodes.stream().mapToInt(c -> roleRank.getOrDefault(c, 0)).max().orElse(0);
        return nodes.size() + maxRank * 10;
    }

    /** 是否不弱于另一条链（加严判定用）。 */
    public boolean atLeastAsStrictAs(ApprovalChain other, Map<String, Integer> roleRank) {
        return this.strictness(roleRank) >= other.strictness(roleRank);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApprovalChain that)) {
            return false;
        }
        return secondConfirm == that.secondConfirm
                && nodes.equals(that.nodes)
                && coversRiskLevel == that.coversRiskLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, secondConfirm, coversRiskLevel);
    }
}
