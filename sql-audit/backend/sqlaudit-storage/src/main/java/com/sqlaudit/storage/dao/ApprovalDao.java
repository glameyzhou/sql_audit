package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.storage.model.Approval;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批记录 DAO（对应 t_approval 表）。
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>同一 {@code (ticketId, nodeOrder)} 多行 = 一组（会签 / 或签）；
 *       {@code listByTicket} 按 (nodeOrder, id) 稳定排序；</li>
 *   <li>{@link #insert} 受唯一约束 {@code (ticket_id, node_order, approver_type, approver_ref)}
 *       保护：并发重复生成审批链时<strong>返回 0 而不是抛异常</strong>，调用方据此判「已有人生成过」；</li>
 *   <li>{@link #updateResult} 为条件更新（{@code WHERE result = fromResult}，通常 fromResult=PENDING）：
 *       影响行数 0 说明已被决策或并发冲突；同时写入<strong>实际决策人</strong> {@code decided_by}
 *       （指派对象 approver_type / approver_ref 不被覆盖）；</li>
 *   <li>{@link #transfer} 是「原记录置 TRANSFERRED + 插入一条指向代理人的 USER 记录」，
 *       <strong>两步必须在同一事务</strong>——只做一半会让该组凭空少一个审批人或多一个。</li>
 * </ul>
 */
public interface ApprovalDao {

    /**
     * 插入审批记录。
     *
     * @return 自增 id；{@code 0} 表示撞唯一约束（同一工单同一组已存在同样的指派对象），
     *         调用方视为「已被并发生成」，不要当成错误
     */
    long insert(Approval approval);

    /** 工单的全部审批记录，按 (nodeOrder, id) 稳定排序；返回值为快照拷贝。 */
    List<Approval> listByTicket(long ticketId);

    /**
     * 条件更新审批结果（PENDING → APPROVED / REJECTED / TRANSFERRED / SKIPPED）。
     *
     * @param decidedBy  实际决策人（写入 decided_by；ROLE 记录由决策人「认领」）
     * @param fromResult 期望的当前结果（条件，通常为 PENDING）
     * @param toResult   目标结果
     * @return 影响行数：1 成功；0 = 已被决策 / 记录不存在（并发冲突）
     */
    int updateResult(long id, long decidedBy, ApprovalResult fromResult, ApprovalResult toResult,
                     String comment, LocalDateTime decidedAt);

    /** 全部 PENDING 审批（server「待我审批」列表；按 (created_at, id) 升序，走 (result, created_at) 索引）。 */
    List<Approval> listPending();

    /**
     * 审批转交（单事务）：把 {@code id} 这条 PENDING 记录置为 TRANSFERRED 留痕
     * （{@code decided_by} = 转交人），并在同组插入一条指向代理人的 USER 记录。
     *
     * <p>代理人资格（须持有同一角色 / 不是提交人 / 不在本组）由 workflow 层校验，
     * DAO 只保证两步的原子性与条件性。</p>
     *
     * @param fromUserId 转交人（写入原记录的 decided_by）
     * @param toUserId   代理人（新记录的 approver_ref）
     * @return 新插入记录的 id；{@code 0} = 原记录已非 PENDING / 不存在，或代理人已在本组（未做任何写入）
     */
    long transfer(long id, long fromUserId, long toUserId, LocalDateTime transferredAt);
}
