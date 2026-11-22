package com.sqlaudit.workflow;

/**
 * 审批模式（设计文档 §8.2 flow_node.mode）。
 *
 * <ul>
 *   <li>{@link #AND}：会签——该节点下<strong>全部</strong>审批人都 APPROVED 才通过，任一 REJECTED 即驳回；</li>
 *   <li>{@link #OR}：或签——任一 APPROVED 即通过；任一 REJECTED 且无人 APPROVED 即驳回（先决者胜）。</li>
 * </ul>
 */
public enum ApprovalMode {
    /** 会签：全部通过才通过，一人驳回即驳回 */
    AND,
    /** 或签：一人通过即通过（一人驳回且无人通过即驳回） */
    OR
}
