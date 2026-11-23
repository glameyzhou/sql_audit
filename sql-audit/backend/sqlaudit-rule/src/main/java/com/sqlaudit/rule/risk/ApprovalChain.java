package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.List;
import java.util.Objects;

/**
 * 审批链（生效链，README §4.3 风险分级 / README §4.6 节点语义）。
 *
 * <p>节点是{@link ChainNode} 序列（USER / ROLE / INSTANCE_OWNER，见 {@link ChainNode}），
 * 同 {@code nodeOrder} 的节点为一组（组内会签 / 或签）。生效链 = 全局链节点组 +
 * 该实例专用链节点组（实例组排在全局组之后，{@link ApprovalRouter} 已重新编号）——
 * 追加语义天然「只会更严」，不再有角色权重 / 链强度比较。</p>
 *
 * <p>{@code secondConfirm} 只由风险等级推导：CRITICAL 一律要求执行前二次确认，
 * 任何流程配置都改不了（README §4.6）。</p>
 *
 * @param nodes        节点序列（有序：按审批组顺序；同 nodeOrder 多节点 = 一组）
 * @param secondConfirm 是否需要执行前二次确认（恒 = riskLevel == CRITICAL）
 * @param riskLevel    本链对应的风险等级
 */
public record ApprovalChain(
        List<ChainNode> nodes,
        boolean secondConfirm,
        RiskLevel riskLevel) {

    public ApprovalChain {
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(riskLevel, "riskLevel");
    }

    /** 空链（无任何节点）。 */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
