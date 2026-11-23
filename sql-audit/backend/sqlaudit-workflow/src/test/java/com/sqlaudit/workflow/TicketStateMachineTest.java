package com.sqlaudit.workflow;


import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TicketStateMachine} 单测（验收：README §4.2 状态机全迁移路径 + 非法迁移抛异常）。
 */
class TicketStateMachineTest {

    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    private final TicketStateMachine stateMachine = new TicketStateMachine(ticketDao);

    // ---- 到达各状态的规范路径（从 draft 出发的合法边序列） ----
    private static final Map<TicketStatus, List<TicketStatus>> PATHS = Map.ofEntries(
            entry(TicketStatus.DRAFT, List.of()),
            entry(TicketStatus.SUBMITTED, List.of(TicketStatus.SUBMITTED)),
            entry(TicketStatus.AUDITING, List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING)),
            entry(TicketStatus.APPROVED, List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED)),
            entry(TicketStatus.REJECTED, List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.REJECTED)),
            entry(TicketStatus.SCHEDULED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED)),
            entry(TicketStatus.EXECUTING,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING)),
            entry(TicketStatus.SUCCEEDED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING, TicketStatus.SUCCEEDED)),
            entry(TicketStatus.PARTIALLY_SUCCEEDED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING, TicketStatus.PARTIALLY_SUCCEEDED)),
            entry(TicketStatus.FAILED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING, TicketStatus.FAILED)),
            entry(TicketStatus.NEEDS_CONFIRM,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING, TicketStatus.NEEDS_CONFIRM)),
            entry(TicketStatus.CLOSED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.SCHEDULED, TicketStatus.EXECUTING, TicketStatus.SUCCEEDED, TicketStatus.CLOSED)),
            entry(TicketStatus.CANCELLED, List.of(TicketStatus.CANCELLED)),
            entry(TicketStatus.EXPIRED,
                    List.of(TicketStatus.SUBMITTED, TicketStatus.AUDITING, TicketStatus.APPROVED, TicketStatus.EXPIRED)));

    /** README §4.2 图的全部 25 条合法边 */
    private static final List<Map.Entry<TicketStatus, TicketStatus>> LEGAL_EDGES = List.of(
            entry(TicketStatus.DRAFT, TicketStatus.SUBMITTED),
            entry(TicketStatus.DRAFT, TicketStatus.CANCELLED),
            entry(TicketStatus.SUBMITTED, TicketStatus.AUDITING),
            entry(TicketStatus.SUBMITTED, TicketStatus.CANCELLED),
            entry(TicketStatus.AUDITING, TicketStatus.APPROVED),
            entry(TicketStatus.AUDITING, TicketStatus.REJECTED),
            entry(TicketStatus.AUDITING, TicketStatus.CANCELLED),
            entry(TicketStatus.APPROVED, TicketStatus.SCHEDULED),
            entry(TicketStatus.APPROVED, TicketStatus.EXECUTING),
            entry(TicketStatus.APPROVED, TicketStatus.EXPIRED),
            entry(TicketStatus.APPROVED, TicketStatus.CANCELLED),
            entry(TicketStatus.REJECTED, TicketStatus.CLOSED),
            entry(TicketStatus.REJECTED, TicketStatus.CANCELLED),
            entry(TicketStatus.SCHEDULED, TicketStatus.EXECUTING),
            entry(TicketStatus.SCHEDULED, TicketStatus.CANCELLED),
            entry(TicketStatus.EXECUTING, TicketStatus.SUCCEEDED),
            entry(TicketStatus.EXECUTING, TicketStatus.PARTIALLY_SUCCEEDED),
            entry(TicketStatus.EXECUTING, TicketStatus.FAILED),
            entry(TicketStatus.EXECUTING, TicketStatus.NEEDS_CONFIRM),
            entry(TicketStatus.SUCCEEDED, TicketStatus.CLOSED),
            entry(TicketStatus.PARTIALLY_SUCCEEDED, TicketStatus.CLOSED),
            entry(TicketStatus.FAILED, TicketStatus.CLOSED),
            entry(TicketStatus.NEEDS_CONFIRM, TicketStatus.SUCCEEDED),
            entry(TicketStatus.NEEDS_CONFIRM, TicketStatus.FAILED),
            entry(TicketStatus.NEEDS_CONFIRM, TicketStatus.CLOSED));

    private Ticket newDraftTicket() {
        Ticket ticket = new Ticket();
        ticket.setTitle("state-machine-test");
        ticket.setCreatorId(1L);
        ticket.setInstanceId(1L);
        
        ticket.setRiskLevel(RiskLevel.HIGH);
        ticket.setStatus(TicketStatus.DRAFT);
        ticket.setTraceId("trace-sm");
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        long id = ticketDao.insertTicket(ticket);
        return ticketDao.findById(id);
    }

    /** 经规范路径把工单推进到 target 状态。 */
    private Ticket atStatus(TicketStatus target) {
        Ticket ticket = newDraftTicket();
        for (TicketStatus step : PATHS.get(target)) {
            stateMachine.transition(ticket, step, "tester");
        }
        assertEquals(target, ticket.getStatus(), "前置路径应到达 " + target);
        return ticket;
    }

    @Test
    void 合法边清单与冻结契约一致() {
        // 冻结契约 TicketStatus 的全部合法边数 == 本测试列举的边数（防契约漂移）
        long contractEdgeCount = Arrays.stream(TicketStatus.values())
                .flatMap(from -> Arrays.stream(TicketStatus.values())
                        .map(from::canTransitionTo)
                        .filter(Boolean::booleanValue))
                .count();
        assertEquals(LEGAL_EDGES.size(), contractEdgeCount, "契约合法边数量应与 README §4.2 图一致");
        for (Map.Entry<TicketStatus, TicketStatus> edge : LEGAL_EDGES) {
            assertTrue(edge.getKey().canTransitionTo(edge.getValue()),
                    edge.getKey() + " → " + edge.getValue() + " 应为合法边");
        }
    }

    @Test
    void 状态机全合法边_每条边迁移成功并落库() {
        for (Map.Entry<TicketStatus, TicketStatus> edge : LEGAL_EDGES) {
            Ticket ticket = atStatus(edge.getKey());
            stateMachine.transition(ticket, edge.getValue(), "tester");
            assertEquals(edge.getValue(), ticket.getStatus(),
                    "边 " + edge.getKey() + " → " + edge.getValue() + " 迁移后内存状态");
            assertEquals(edge.getValue(), ticketDao.findById(ticket.getId()).getStatus(),
                    "边 " + edge.getKey() + " → " + edge.getValue() + " 迁移后落库状态");
        }
    }

    @Test
    void 非法迁移_全部非法组合均抛IllegalStateTransitionException() {
        // 覆盖契约中全部非法组合（含验收点名的 closed→draft、rejected→executing、succeeded→draft）
        for (TicketStatus from : TicketStatus.values()) {
            Ticket ticket = atStatus(from);
            for (TicketStatus to : TicketStatus.values()) {
                if (from.canTransitionTo(to)) {
                    continue;
                }
                IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                        () -> stateMachine.transition(ticket, to, "tester"),
                        from + " → " + to + " 应为非法迁移");
                assertTrue(ex.getMessage().contains(from.name()) && ex.getMessage().contains(to.name()),
                        "异常消息应含迁移两端：" + ex.getMessage());
            }
            // 非法迁移不得改变状态（不是静默忽略，也不是部分生效）
            assertEquals(from, ticketDao.findById(ticket.getId()).getStatus(),
                    from + " 经历全部非法迁移尝试后状态不变");
        }
    }

    @Test
    void 验收点名的三条非法迁移_直接断言() {
        assertThrows(IllegalStateTransitionException.class,
                () -> stateMachine.transition(atStatus(TicketStatus.CLOSED), TicketStatus.DRAFT, "t"));
        assertThrows(IllegalStateTransitionException.class,
                () -> stateMachine.transition(atStatus(TicketStatus.REJECTED), TicketStatus.EXECUTING, "t"));
        assertThrows(IllegalStateTransitionException.class,
                () -> stateMachine.transition(atStatus(TicketStatus.SUCCEEDED), TicketStatus.DRAFT, "t"));
    }

    @Test
    void 监听器_每次迁移回调ticketId与from与to与actor() {
        List<String> events = new ArrayList<>();
        TicketStateMachine sm = new TicketStateMachine(ticketDao,
                (ticketId, from, to, actor) -> events.add(ticketId + ":" + from + ">" + to + ":" + actor));

        Ticket ticket = newDraftTicket();
        sm.transition(ticket, TicketStatus.SUBMITTED, "u1");
        sm.transition(ticket, TicketStatus.AUDITING, "u1");
        sm.transition(ticket, TicketStatus.APPROVED, "u2");

        assertEquals(List.of(
                ticket.getId() + ":DRAFT>SUBMITTED:u1",
                ticket.getId() + ":SUBMITTED>AUDITING:u1",
                ticket.getId() + ":AUDITING>APPROVED:u2"), events);
    }

    @Test
    void 乐观并发_条件更新影响行数0抛冲突异常() {
        Ticket ticket = atStatus(TicketStatus.DRAFT);

        // 模拟并发：另一个调用方先把 draft → submitted 落库
        assertEquals(1, ticketDao.updateStatus(ticket.getId(), TicketStatus.DRAFT, TicketStatus.SUBMITTED,
                LocalDateTime.now()));

        // 手里的快照仍是 draft：canTransitionTo 通过，但条件更新影响行数 0 → 冲突异常
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> stateMachine.transition(ticket, TicketStatus.SUBMITTED, "u1"));
        assertEquals("TICKET_STATE_CONFLICT", ex.getCode());
        assertEquals(TicketStatus.SUBMITTED, ticketDao.findById(ticket.getId()).getStatus());
    }

    @Test
    void 编解码_存储码fromCode往返() {
        for (TicketStatus status : TicketStatus.values()) {
            assertEquals(status, TicketStatus.fromCode(status.getCode()));
        }
        // 未知取值在编解码入口即抛异常，不会被静默吞掉
        assertThrows(IllegalArgumentException.class, () -> TicketStatus.fromCode("no_such_status"));
    }
}
