package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import java.time.LocalDateTime;

/**
 * 审批记录实体（对应 t_approval 表）——提交工单时按生效链一次性生成。
 *
 * <p>同一 {@code (ticketId, nodeOrder)} 下多行 = 一组（会签 / 或签）。生效链 =
 * 全局链节点组 + 实例链节点组，{@code nodeOrder} 是<strong>重新编号后</strong>的组序
 * （全局组在前、实例组在后），不等于 {@code t_flow_node.node_order}。</p>
 *
 * <p><strong>指派对象与决策人分列存储</strong>（消除旧模型里 {@code approver_id = 0} /
 * {@code node_id = 0} 的魔数）：</p>
 * <ul>
 *   <li>{@code flowNodeId}：生成本记录的 {@code t_flow_node.id}——流程配置可被改写，
 *       留下它才能复现「当时用的是哪条配置」；</li>
 *   <li>{@code approverType} + {@code approverRef}：<strong>指派对象</strong>，两者都 NOT NULL。
 *       USER = 用户 id；ROLE = 角色 code；INSTANCE_OWNER = 提交时解析出的负责人用户 id（快照）。
 *       引擎对缺失值一律拒绝决策，不再有「ref 为 NULL 就跳过角色校验」的越权口子；</li>
 *   <li>{@code decidedBy}：<strong>实际决策人</strong>（认领 ROLE 记录、决策、转交、跳过时写入），
 *       与指派信息互不覆盖。</li>
 * </ul>
 *
 * <p>唯一约束 {@code (ticket_id, node_order, approver_type, approver_ref)} 防并发重复生成。</p>
 */
public class Approval {

    private Long id;
    private Long ticketId;
    /** 生成本记录的 t_flow_node.id（审批配置可追溯） */
    private Long flowNodeId;
    /** 生效链中的组序，1..n；同值多行 = 会签/或签组 */
    private Integer nodeOrder;
    /** AND 会签 / OR 或签 */
    private ApprovalMode mode;
    /** 指派对象类型（USER/ROLE/INSTANCE_OWNER） */
    private ApproverType approverType;
    /** 指派对象：USER=用户 id；ROLE=角色 code；INSTANCE_OWNER=负责人用户 id（快照） */
    private String approverRef;
    /** PENDING/APPROVED/REJECTED/TRANSFERRED/SKIPPED */
    private ApprovalResult result;
    /** 实际决策人（认领 / 决策 / 转交 / 跳过时写入） */
    private Long decidedBy;
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

    public Long getFlowNodeId() {
        return flowNodeId;
    }

    public void setFlowNodeId(Long flowNodeId) {
        this.flowNodeId = flowNodeId;
    }

    public Integer getNodeOrder() {
        return nodeOrder;
    }

    public void setNodeOrder(Integer nodeOrder) {
        this.nodeOrder = nodeOrder;
    }

    public ApprovalMode getMode() {
        return mode;
    }

    public void setMode(ApprovalMode mode) {
        this.mode = mode;
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

    public ApprovalResult getResult() {
        return result;
    }

    public void setResult(ApprovalResult result) {
        this.result = result;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Long decidedBy) {
        this.decidedBy = decidedBy;
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
