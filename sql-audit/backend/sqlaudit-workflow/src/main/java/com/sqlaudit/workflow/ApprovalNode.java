package com.sqlaudit.workflow;

/**
 * 审批节点扩展模型（workflow 层，设计文档 §8.2 flow_node 的内存映射）。
 *
 * <p>approval 表没有 approver_type / 角色字段，B-3 用本模型承载节点语义，
 * 映射到 approval 记录时 {@code approver_id = 0} 表示「按角色解析」（见
 * {@link ApprovalFlowEngine#createNodes}）。</p>
 *
 * <p>B-3 阶段 approverType 只支持 {@link #TYPE_ROLE}（审批链节点为角色，如 LEAD/DBA/ADMIN）；
 * USER / INSTANCE_OWNER 与角色 → 用户解析由 W2 装配时补。</p>
 *
 * @param nodeOrder    节点顺序（1..n，节点串行推进）
 * @param approverType USER / ROLE / INSTANCE_OWNER（§8.2）
 * @param role         角色 code（approverType=ROLE 时有效）
 * @param mode         会签 / 或签
 */
public record ApprovalNode(int nodeOrder, String approverType, String role, ApprovalMode mode) {

    /** flow_node.approver_type=ROLE：按角色解析审批人 */
    public static final String TYPE_ROLE = "ROLE";
}
