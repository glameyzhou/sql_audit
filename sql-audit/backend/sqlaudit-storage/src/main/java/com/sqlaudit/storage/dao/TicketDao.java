package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Ticket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单 DAO 接口（设计文档 §3.2 工单状态机 / §9 ticket 表）。
 *
 * <p><strong>B-3 新增接口，不含实现</strong>（风格对齐 {@link TicketUnitDao}）；
 * W2 集成时换 MyBatis 真实现。</p>
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>{@code updateStatus} 为<strong>条件更新</strong>（{@code WHERE status = fromStatus}）：
 *       影响行数 0 说明状态已被并发修改或不存在——调用方（状态机）必须视为乐观并发冲突抛异常，
 *       不得静默忽略；</li>
 *   <li>{@code status} 为小写字符串（§3.2），与 {@link TicketUnitDao} 的状态列风格一致。</li>
 * </ul>
 */
public interface TicketDao {

    /**
     * 插入工单，返回自增 id（同时回写到传入实体的 id 字段由实现决定，调用方以返回值为准）。
     */
    long insertTicket(Ticket ticket);

    /** 按主键查询；不存在返回 null。返回值为快照拷贝。 */
    Ticket findById(long id);

    /**
     * 条件更新状态（乐观并发）：仅当当前 status == fromStatus 时更新为 toStatus，
     * 并把 updated_at 置为 changedAt（作为审批时效 approval_ttl 的基准，§3.5）。
     *
     * @return 影响行数：1 成功；0 = 状态已变 / 工单不存在（并发冲突）
     */
    int updateStatus(long id, String fromStatus, String toStatus, LocalDateTime changedAt);

    /** 按创建人查询（「我的工单」列表），created_at 倒序（最新在前）。 */
    List<Ticket> listByCreator(long creatorId);

    /** 按状态查询（reaper / 到期扫描用，如 status='approved' 的超时检测）。 */
    List<Ticket> listByStatus(String status);
}
