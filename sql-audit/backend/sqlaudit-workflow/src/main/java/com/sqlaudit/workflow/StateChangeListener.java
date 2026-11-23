package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.TicketStatus;

/**
 * 工单状态变更监听器（README §4.2 状态机 / README §4.1 审计）。
 *
 * <p>所有状态变更的审计写入钩子：workflow 不直接依赖 sqlaudit-audit（模块边界），
 * W2 装配时由装配层把本监听器接到 B-2 的 AuditWriter 上。</p>
 *
 * <p>监听器异常会向上传播（审计写入失败不应被静默吞掉）；如需容错由实现方自行捕获。</p>
 */
@FunctionalInterface
public interface StateChangeListener {

    /**
     * 工单状态已从 {@code from} 迁移为 {@code to}（迁移已落库成功后回调）。
     *
     * @param ticketId 工单 id
     * @param from     迁移前状态
     * @param to       迁移后状态
     * @param actor    操作人（userId 字符串 / SYSTEM 等系统动作标识）
     */
    void onStateChanged(long ticketId, TicketStatus from, TicketStatus to, String actor);
}
