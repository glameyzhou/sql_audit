package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 工单状态机（设计文档 §3.2，B-3 验收核心）。
 *
 * <p>迁移合法性判定复用已冻结契约 {@link TicketStatus#canTransitionTo(TicketStatus)}：
 * 非法迁移<strong>抛 {@link IllegalStateTransitionException}，绝不静默忽略</strong>；
 * 合法迁移通过 {@link TicketDao#updateStatus} 的条件更新落库（乐观并发），
 * 影响行数 0 说明状态已被并发修改 → 抛冲突异常。</p>
 *
 * <p>状态在 DB 中为小写字符串（§3.2），本类提供 {@link #dbValue(TicketStatus)} /
 * {@link #parse(String)} 两个编解码入口供 workflow 各处复用。</p>
 *
 * <p>审计钩子：{@link StateChangeListener}（可选注入），W2 装配时接 sqlaudit-audit 的
 * AuditWriter；workflow 模块不直接依赖 audit。</p>
 */
public class TicketStateMachine {

    private final TicketDao ticketDao;
    private final List<StateChangeListener> listeners;

    public TicketStateMachine(TicketDao ticketDao, StateChangeListener... listeners) {
        this.ticketDao = Objects.requireNonNull(ticketDao, "ticketDao");
        this.listeners = List.of(listeners);
    }

    /**
     * 迁移工单状态。
     *
     * @param ticket 工单快照（迁移成功后其 status 字段同步更新，供调用方继续使用）
     * @param target 目标状态
     * @param actor  操作人（审计用）
     * @throws IllegalStateTransitionException 非法迁移（canTransitionTo 不通过）
     * @throws SqlauditException               code=TICKET_STATE_CONFLICT，条件更新影响行数 0（并发冲突）
     */
    public void transition(Ticket ticket, TicketStatus target, String actor) {
        Objects.requireNonNull(ticket, "ticket");
        TicketStatus from = parse(ticket.getStatus());

        // 非法迁移必须抛异常而不是静默忽略（§B-3 验收）
        if (!from.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(from.name(), target.name());
        }

        // 条件更新：WHERE status = from，影响行数 0 = 并发冲突
        int rows = ticketDao.updateStatus(ticket.getId(), ticket.getStatus(), dbValue(target),
                LocalDateTime.now());
        if (rows == 0) {
            throw new SqlauditException("TICKET_STATE_CONFLICT",
                    "工单 " + ticket.getId() + " 状态并发冲突：" + ticket.getStatus() + " → " + dbValue(target));
        }

        ticket.setStatus(dbValue(target));
        for (StateChangeListener listener : listeners) {
            listener.onStateChanged(ticket.getId(), from, target, actor);
        }
    }

    /** DB 存储值：小写（§3.2），与 ticket_unit 状态列风格一致。 */
    public static String dbValue(TicketStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** 解析 DB 存储值；非法值抛 code=UNKNOWN_TICKET_STATUS。 */
    public static TicketStatus parse(String dbValue) {
        try {
            return TicketStatus.valueOf(dbValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SqlauditException("UNKNOWN_TICKET_STATUS", "未知工单状态：" + dbValue, e);
        }
    }
}
