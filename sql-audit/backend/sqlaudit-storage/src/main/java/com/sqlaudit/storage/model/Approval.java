package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import java.time.LocalDateTime;

/**
 * 审批记录实体（对应 approval 表，设计文档 §8.2 / §9）。
 *
 * <p>同一 {@code (ticketId, nodeOrder)} 下多行 = 会签 / 或签组（DDL 注释）。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code mode}：{@link com.sqlaudit.common.enums.ApprovalMode}，AND 会签 / OR 或签（存储码 = 枚举名）；</li>
 *   <li>{@code result}：{@link com.sqlaudit.common.enums.ApprovalResult}，PENDING / APPROVED / REJECTED / TRANSFERRED；</li>
 *   <li>{@code approverId}：B-3 阶段审批链节点为 ROLE 占位，取 {@code 0} 表示「按角色解析」，
 *       W2 装配时由 workflow 补角色 → 用户解析（§8.2 flow_node.approver_type=ROLE）；
 *       决策时 updateResult 会把实际决策人写入本字段。</li>
 * </ul>
 */
public class Approval {

    private Long id;
    private Long ticketId;
    /** 关联 flow_node；B-3 无 flow_node 记录，占位 0，W2 装配时补 */
    private Long nodeId;
    /** 节点顺序，1..n；同 nodeOrder 多行 = 会签/或签组 */
    private Integer nodeOrder;
    /** 审批人；ROLE 占位节点为 0（按角色解析） */
    private Long approverId;
    /** 节点审批人类型（ROLE/USER/INSTANCE_OWNER，§8.2） */
    private ApproverType approverType;
    /** 审批人 ref（ROLE=角色码，USER=用户id，INSTANCE_OWNER=owner） */
    private String approverRef;
    /** AND 会签 / OR 或签 */
    private ApprovalMode mode;
    /** PENDING/APPROVED/REJECTED/TRANSFERRED */
    private ApprovalResult result;
    private String comment;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getNodeOrder() {
        return nodeOrder;
    }

    public void setNodeOrder(Integer nodeOrder) {
        this.nodeOrder = nodeOrder;
    }

    public Long getApproverId() {
        return approverId;
    }

    public void setApproverId(Long approverId) {
        this.approverId = approverId;
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

    public ApprovalResult getResult() {
        return result;
    }

    public void setResult(ApprovalResult result) {
        this.result = result;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
