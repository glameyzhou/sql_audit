package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import java.time.LocalDateTime;

/**
 * 审批节点实体（对应 t_flow_node 表）。
 *
 * <p>同一 {@code nodeOrder} 的多行 = 一组，组内 {@code mode} 必须一致
 * （AND 会签 / OR 或签）；{@code nodeOrder} 从 1 连续。</p>
 *
 * <p>{@code approverRef} 的解释由 {@code approverType} 决定：USER = 用户 id，
 * ROLE = 角色 code，INSTANCE_OWNER = 固定 {@code *}（提交工单时才解析为实例负责人）。</p>
 */
public class FlowNode {

    /** INSTANCE_OWNER 节点的 approver_ref 固定值。 */
    public static final String INSTANCE_OWNER_REF = "*";

    private Long id;
    private Long flowRuleId;
    /** 组序，从 1 连续；同值为一组 */
    private Integer nodeOrder;
    private ApproverType approverType;
    /** USER=user_id；ROLE=角色 code；INSTANCE_OWNER=* */
    private String approverRef;
    /** AND 会签 / OR 或签；同组必须一致 */
    private ApprovalMode mode;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFlowRuleId() {
        return flowRuleId;
    }

    public void setFlowRuleId(Long flowRuleId) {
        this.flowRuleId = flowRuleId;
    }

    public Integer getNodeOrder() {
        return nodeOrder;
    }

    public void setNodeOrder(Integer nodeOrder) {
        this.nodeOrder = nodeOrder;
    }

    public ApproverType getApproverType() {
        return approverType;
    }

    public void setApproverType(ApproverType approverType) {
        this.approverType = approverType;
    }

    public String getApproverRef() {
        return approverRef;
    }

    public void setApproverRef(String approverRef) {
        this.approverRef = approverRef;
    }

    public ApprovalMode getMode() {
        return mode;
    }

    public void setMode(ApprovalMode mode) {
        this.mode = mode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
