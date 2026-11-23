package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.model.Ticket;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单 DAO（对应 t_ticket 表）。
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>{@code updateStatus} 为<strong>条件更新</strong>（{@code WHERE status = fromStatus}）：
 *       影响行数 0 说明状态已被并发修改或不存在——调用方（状态机）必须视为乐观并发冲突抛异常，
 *       不得静默忽略；</li>
 *   <li>{@code status} 在 Java 侧为 {@link TicketStatus} 枚举，DB 中存其小写存储码，
 *       转换只在 DAO 边界做；</li>
 *   <li>{@code sqlText} 模型里是明文，JDBC 实现落库前用 {@code SqlCipher} 加密、读出后解密。</li>
 * </ul>
 */
public interface TicketDao {

    /** 插入工单，返回自增 id（并回填到传入实体）。 */
    long insertTicket(Ticket ticket);

    /** 按主键查询；不存在返回 null。返回值为快照拷贝。 */
    Ticket findById(long id);

    /**
     * 条件更新状态（乐观并发）：仅当当前 status == fromStatus 时更新为 toStatus，
     * 并把 updated_at 置为 changedAt（作为审批时效 approval_ttl 的基准）。
     *
     * @return 影响行数：1 成功；0 = 状态已变 / 工单不存在（并发冲突）
     */
    int updateStatus(long id, TicketStatus fromStatus, TicketStatus toStatus, LocalDateTime changedAt);

    /** 按创建人查询（「我的工单」列表），created_at 倒序（最新在前）。 */
    List<Ticket> listByCreator(long creatorId);

    /**
     * 「我的工单」分页读取（服务端分页），created_at 倒序（最新在前）。
     * {@code offset} 从 0 起；{@code limit < 0} 视为取全部。
     */
    List<Ticket> listByCreator(long creatorId, int offset, int limit);

    /** 「我的工单」总数。 */
    long countByCreator(long creatorId);

    /** 全部工单（「全部工单」列表，调用方须先确认有该能力），created_at 倒序。 */
    List<Ticket> listAll();

    /** 全部工单分页读取（服务端分页），created_at 倒序。{@code limit < 0} 视为取全部。 */
    List<Ticket> listAll(int offset, int limit);

    /** 全部工单总数。 */
    long countAll();

    /**
     * 按实例集合查询（「全部工单」在数据范围受限时用），created_at 倒序。
     * {@code instanceIds} 为空集合时返回空列表（不等于「不过滤」）。
     */
    List<Ticket> listByInstanceIds(List<Long> instanceIds);

    /**
     * 按实例集合分页读取（服务端分页），created_at 倒序。
     * {@code instanceIds} 为空集合时返回空列表（不等于「不过滤」）；{@code limit < 0} 视为取全部。
     */
    List<Ticket> listByInstanceIds(List<Long> instanceIds, int offset, int limit);

    /** 按实例集合计数；{@code instanceIds} 为空集合时返回 0（不等于「不过滤」）。 */
    long countByInstanceIds(List<Long> instanceIds);

    /** 按状态查询（reaper / 到期扫描用，如 status='approved' 的超时检测）。 */
    List<Ticket> listByStatus(TicketStatus status);

    /** 标记是否需要执行前二次确认（生成审批链时写入，CRITICAL 链）。 */
    int markNeedsSecondConfirm(long id, boolean required);

    /** 记录执行前二次确认人 / 时间（confirmExecution 时写入）。 */
    int updateSecondConfirm(long id, Long confirmedBy, LocalDateTime confirmedAt);
}
