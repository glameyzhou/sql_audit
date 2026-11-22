package com.sqlaudit.workflow;

/**
 * 审批结果（设计文档 §9 approval.result）。
 *
 * <ul>
 *   <li>{@link #PENDING}：待审批；</li>
 *   <li>{@link #APPROVED}：同意；</li>
 *   <li>{@link #REJECTED}：驳回（工单转 rejected）；</li>
 *   <li>{@link #TRANSFERRED}：转交（B-3 仅保留语义与 DAO 条件更新支持，转交操作由 W2 实现）。</li>
 * </ul>
 */
public enum ApprovalResult {
    PENDING,
    APPROVED,
    REJECTED,
    TRANSFERRED
}
