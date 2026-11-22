package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.List;
import java.util.Objects;

/**
 * 审批链（设计文档 §4.5 / §8.2）。
 *
 * <p>节点为角色序列，如 [LEAD, DBA]。强度比较：更长的链且包含更高级角色 = 更严
 * （ADMIN &gt; DBA &gt; LEAD）。</p>
 *
 * @param nodes            节点角色序列（有序：按审批顺序）
 * @param secondConfirm    是否需要执行前二次确认（CRITICAL 专属，§3.4）
 * @param source           生效来源：L1_GLOBAL / L2_INSTANCE / L3_FALLBACK
 * @param coversRiskLevel  本链覆盖的风险等级
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
    public int strictness() {
        int maxRank = nodes.stream().mapToInt(ApprovalChain::roleRank).max().orElse(0);
        return nodes.size() + maxRank * 10;
    }

    /** 是否不弱于另一条链（加严判定用）。 */
    public boolean atLeastAsStrictAs(ApprovalChain other) {
        return this.strictness() >= other.strictness();
    }

    private static int roleRank(String role) {
        return switch (role) {
            case "ADMIN" -> 3;
            case "DBA" -> 2;
            case "LEAD" -> 1;
            default -> 0; // INSTANCE_OWNER / 自定义角色按普通节点计
        };
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
