package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalResult;

/**
 * 一次审批决策的结果快照（{@link ApprovalFlowEngine#submitDecision} 返回值）。
 *
 * @param ticketId       工单 id
 * @param nodeOrder      决策发生的节点
 * @param approverId     实际决策人
 * @param result         本条决策结果（APPROVED / REJECTED）
 * @param nodePassed     该节点是否通过（会签需全部同意；或签一人同意即通过）
 * @param ticketApproved 工单是否已转 approved（全部节点通过；审批 ≠ 执行，README §4.6）
 * @param ticketRejected 工单是否已转 rejected（任一节点驳回）
 */
public record ApprovalDecision(long ticketId, int nodeOrder, long approverId, ApprovalResult result,
                               boolean nodePassed, boolean ticketApproved, boolean ticketRejected) {
}
