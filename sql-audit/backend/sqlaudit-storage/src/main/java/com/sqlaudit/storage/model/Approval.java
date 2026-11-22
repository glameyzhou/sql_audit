package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 审批记录实体（对应 approval 表，设计文档 §8.2 / §9）。
 *
 * <p>同一 {@code (ticketId, nodeOrder)} 下多行 = 会签 / 或签组（DDL 注释）。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code mode}：AND 会签 / OR 或签（大写字符串）；</li>
 *   <li>{@code result}：PENDING / APPROVED / REJECTED / TRANSFERRED（大写字符串）；</li>
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
    /** AND 会签 / OR 或签 */
    private String mode;
    /** PENDING/APPROVED/REJECTED/TRANSFERRED */
    private String result;
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

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
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
