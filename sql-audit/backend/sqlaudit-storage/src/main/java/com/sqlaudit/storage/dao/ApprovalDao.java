package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.storage.model.Approval;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批记录 DAO 接口（设计文档 §8.2 / §9 approval 表）。
 *
 * <p><strong>B-3 新增接口，不含实现</strong>（风格对齐 {@link TicketUnitDao}）；
 * W2 集成时换 MyBatis 真实现。</p>
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>同一 {@code (ticketId, nodeOrder)} 多行 = 会签 / 或签组（DDL 注释）；
 *       {@code listByTicket} 按 nodeOrder 再按 id 稳定排序；</li>
 *   <li>{@code updateResult} 为<strong>条件更新</strong>（{@code WHERE result = fromResult}，
 *       通常 fromResult=PENDING）：影响行数 0 说明已被决策或并发冲突；
 *       同时把实际决策人 approverId 写入记录（ROLE 占位记录被决策人「认领」）。</li>
 * </ul>
 */
public interface ApprovalDao {

    /** 插入审批记录，返回自增 id。 */
    long insert(Approval approval);

    /** 工单的全部审批记录，按 (nodeOrder, id) 稳定排序；返回值为快照拷贝。 */
    List<Approval> listByTicket(long ticketId);

    /**
     * 条件更新审批结果（PENDING → APPROVED / REJECTED / TRANSFERRED）。
     *
     * @param approverId 实际决策人（写入记录；ROLE 占位记录由决策人认领）
     * @param fromResult 期望的当前结果（条件，通常为 PENDING）
     * @param toResult   目标结果
     * @return 影响行数：1 成功；0 = 已被决策 / 记录不存在（并发冲突）
     */
    int updateResult(long id, long approverId, ApprovalResult fromResult, ApprovalResult toResult,
                     String comment, LocalDateTime decidedAt);

    /**
     * 超时提醒查询（§8.2 flow_node 超时企微提醒）：PENDING 且 created_at 早于
     * threshold 的记录。是否「当前节点」由 workflow 层判定（节点串行）。
     */
    List<Approval> listPendingCreatedBefore(LocalDateTime threshold);

    /** 全部 PENDING 审批（server「待我审批」列表；按 created_at, id 排序）。 */
    List<Approval> listPending();

    /**
     * 审批转交：把某审批人的 PENDING 记录转给代理人（条件更新，fromUserId 不匹配或已决策返回 0）。
     */
    int transfer(long id, long fromUserId, long toUserId);
}
