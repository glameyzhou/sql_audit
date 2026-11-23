package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 审批路由（追加模式，决定 9）。
 *
 * <p>生效链 = 全局链节点组 + 实例专用链节点组（实例组接在全局组<strong>之后</strong>，
 * {@code nodeOrder} 重新编号：全局组的组序 1..n 保持不变，实例组的组序接着
 * n+1..n+k）——天然只会加严，实例配置无法放宽全局要求。旧的「链强度比较 / 只可加严 /
 * L3 兜底 / 角色权重」全部删除。</p>
 *
 * <p>{@code secondConfirm} 恒 = {@code riskLevel == CRITICAL}（README §4.6：CRITICAL 一律
 * 强制执行前二次确认），与流程配置无关。</p>
 *
 * <p>路由只负责拼接；空链（非 LOW）与「无可审人」的拒绝由 workflow 层在
 * 生成审批记录时判定（{@code APPROVAL_FLOW_NOT_CONFIGURED} /
 * {@code NO_ELIGIBLE_APPROVER}）。</p>
 */
public class ApprovalRouter {

    private final FlowConfig config;

    public ApprovalRouter(FlowConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 路由生效链。
     *
     * @param riskLevel  风险等级（RiskAssessor 产出）
     * @param instanceId 目标实例（单实例工单）
     * @return 生效链 = 全局节点组 + 实例节点组（重新编号后）；secondConfirm = CRITICAL
     */
    public ApprovalChain route(RiskLevel riskLevel, long instanceId) {
        Objects.requireNonNull(riskLevel, "riskLevel");
        List<ChainNode> global = config.globalNodes(riskLevel);
        List<ChainNode> instance = config.instanceNodes(instanceId, riskLevel);

        if (instance.isEmpty()) {
            return new ApprovalChain(global, riskLevel == RiskLevel.CRITICAL, riskLevel);
        }

        // 实例组 node_order 接在全局组之后（全局组最后一组的组序为 offset）
        int offset = global.stream().mapToInt(ChainNode::nodeOrder).max().orElse(0);
        List<ChainNode> merged = new ArrayList<>(global.size() + instance.size());
        merged.addAll(global);
        for (ChainNode node : instance) {
            merged.add(new ChainNode(offset + node.nodeOrder(), node.flowNodeId(),
                    node.type(), node.ref(), node.mode()));
        }
        return new ApprovalChain(merged, riskLevel == RiskLevel.CRITICAL, riskLevel);
    }
}
