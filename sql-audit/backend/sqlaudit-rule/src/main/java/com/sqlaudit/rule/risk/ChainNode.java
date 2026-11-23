package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;

/**
 * 审批链节点（审批流配置的节点，随 {@link ApprovalChain} 流转）。
 *
 * <p>对应 {@code t_flow_node} 的内存形态。{@code nodeOrder} 同值为一组（组内会签 / 或签）；
 * {@link ApprovalRouter} 把实例专用链拼在全局链之后时会<strong>重新编号</strong>
 * {@code nodeOrder}（全局组在前、实例组在后），{@code flowNodeId} 始终保留真实的
 * {@code t_flow_node.id}——审批记录据此追溯「当时用的是哪条流程配置」。</p>
 *
 * @param nodeOrder  生效链中的组序（1..n；同值多节点 = 一组）
 * @param flowNodeId 来源 {@code t_flow_node.id}（审计可追溯）
 * @param type       USER / ROLE / INSTANCE_OWNER（见 {@link ApproverType}）
 * @param ref        USER = 用户 id；ROLE = 角色 code；INSTANCE_OWNER = {@code *}
 * @param mode       组内会签（AND）/ 或签（OR）；同组必须一致
 */
public record ChainNode(int nodeOrder, long flowNodeId, ApproverType type, String ref,
                        ApprovalMode mode) {

    public ChainNode {
        if (nodeOrder < 1) {
            throw new IllegalArgumentException("nodeOrder 必须 >= 1: " + nodeOrder);
        }
    }
}
