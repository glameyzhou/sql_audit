package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalFlowEngine} 单测（B-3 验收：防自审 / 会签或签 / 二次确认 / 超时查询）。
 */
class ApprovalFlowEngineTest {

    private static final long CREATOR = 1L;

    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    private final InMemoryApprovalDao approvalDao = new InMemoryApprovalDao();
    private final ApprovalFlowEngine engine = new ApprovalFlowEngine(
            approvalDao, ticketDao, new TicketStateMachine(ticketDao));

    // ---- 造数 ----

    private Ticket newAuditingTicket(RiskLevel riskLevel) {
        Ticket ticket = new Ticket();
        ticket.setTitle("approval-test");
        ticket.setCreatorId(CREATOR);
        ticket.setInstanceIds(new ArrayList<>(List.of(1L)));
        ticket.setEnvironment(Environment.PROD);
        ticket.setRiskLevel(riskLevel);
        ticket.setStatus(TicketStatus.AUDITING);
        ticket.setTraceId("tr-" + System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        long id = ticketDao.insertTicket(ticket);
        return ticketDao.findById(id);
    }

    /** 直接落一条具体审批人的 PENDING 记录（模拟 W2 角色 → 用户解析后的会签组）。 */
    private void seedApproval(long ticketId, int nodeOrder, long approverId, ApprovalMode mode,
                              LocalDateTime createdAt) {
        Approval approval = new Approval();
        approval.setTicketId(ticketId);
        approval.setNodeId(0L);
        approval.setNodeOrder(nodeOrder);
        approval.setApproverId(approverId);
        approval.setMode(mode);
        approval.setResult(ApprovalResult.PENDING);
        approval.setCreatedAt(createdAt);
        approvalDao.insert(approval);
    }

    private TicketStatus statusOf(Ticket ticket) {
        return ticketDao.findById(ticket.getId()).getStatus();
    }

    // ---- createNodes ----

    @Test
    void createNodes_多节点链_生成AND会签占位记录() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        ApprovalChain chain = new ApprovalChain(List.of("LEAD", "DBA"), false, "L1_GLOBAL",
                RiskLevel.HIGH);

        List<Approval> nodes = engine.createNodes(ticket, chain);

        assertEquals(2, nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Approval node = nodes.get(i);
            assertEquals(i + 1, node.getNodeOrder());
            assertEquals(ApprovalMode.AND, node.getMode(), "多节点链默认会签");
            assertEquals(0L, node.getApproverId(), "ROLE 占位：approver_id=0 表示按角色解析");
            assertEquals(ApprovalResult.PENDING, node.getResult());
            assertEquals(0L, node.getNodeId(), "flow_node 关联占位，W2 装配时补");
        }
        // buildNodes 的角色顺序与链一致
        List<ApprovalNode> built = engine.buildNodes(chain);
        assertEquals("LEAD", built.get(0).role());
        assertEquals("DBA", built.get(1).role());
        assertEquals(ApproverType.ROLE, built.get(0).approverType());
    }

    @Test
    void createNodes_单节点链_生成OR或签记录() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM);
        ApprovalChain chain = new ApprovalChain(List.of("LEAD"), false, "L1_GLOBAL", RiskLevel.MEDIUM);

        List<Approval> nodes = engine.createNodes(ticket, chain);

        assertEquals(1, nodes.size());
        assertEquals(ApprovalMode.OR, nodes.get(0).getMode(), "单节点链映射为或签");
    }

    @Test
    void createNodes_重复生成抛异常() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        ApprovalChain chain = new ApprovalChain(List.of("LEAD", "DBA"), false, "L1_GLOBAL",
                RiskLevel.HIGH);
        engine.createNodes(ticket, chain);

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.createNodes(ticket, chain));
        assertEquals("APPROVAL_NODES_EXIST", ex.getCode());
    }

    // ---- 防自审（§3.4 硬校验） ----

    @Test
    void 防自审_提交人审批自己的工单必须被拒() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        engine.createNodes(ticket,
                new ApprovalChain(List.of("LEAD"), false, "L1_GLOBAL", RiskLevel.MEDIUM));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), CREATOR, List.of(), true, "自己批自己"));
        assertEquals("SELF_APPROVAL_FORBIDDEN", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
        assertEquals(ApprovalResult.PENDING, approvalDao.listByTicket(ticket.getId()).get(0).getResult());
    }

    // ---- 会签 / 或签 ----

    @Test
    void 会签_AND_两审批人全过才过节点并转approved() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        LocalDateTime now = LocalDateTime.now();
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.AND, now);
        seedApproval(ticket.getId(), 1, 3L, ApprovalMode.AND, now);

        // 第一人同意：节点未过，工单仍在审批
        ApprovalDecision first = engine.submitDecision(ticket.getId(), 2L, List.of(), true, "ok");
        assertFalse(first.nodePassed());
        assertFalse(first.ticketApproved());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));

        // 第二人同意：节点通过（唯一节点）→ 工单 approved
        ApprovalDecision second = engine.submitDecision(ticket.getId(), 3L, List.of(), true, "ok");
        assertTrue(second.nodePassed());
        assertTrue(second.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 会签_AND_一人驳回即驳回工单() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        LocalDateTime now = LocalDateTime.now();
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.AND, now);
        seedApproval(ticket.getId(), 1, 3L, ApprovalMode.AND, now);

        engine.submitDecision(ticket.getId(), 2L, List.of(), true, "ok");
        ApprovalDecision reject = engine.submitDecision(ticket.getId(), 3L, List.of(), false, "风险太高");

        assertTrue(reject.ticketRejected());
        assertEquals(TicketStatus.REJECTED, statusOf(ticket));
    }

    @Test
    void 或签_OR_一人通过即过节点() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM);
        LocalDateTime now = LocalDateTime.now();
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.OR, now);
        seedApproval(ticket.getId(), 1, 3L, ApprovalMode.OR, now);

        ApprovalDecision decision = engine.submitDecision(ticket.getId(), 2L, List.of(), true, "ok");
        assertTrue(decision.nodePassed());
        assertTrue(decision.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 或签_OR_一人驳回且无人通过即驳回() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM);
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.OR, LocalDateTime.now());
        seedApproval(ticket.getId(), 1, 3L, ApprovalMode.OR, LocalDateTime.now());

        ApprovalDecision decision = engine.submitDecision(ticket.getId(), 2L, List.of(), false, "不批");
        assertTrue(decision.ticketRejected());
        assertEquals(TicketStatus.REJECTED, statusOf(ticket));
    }

    @Test
    void 多节点串行_前一节点通过后才轮到下一节点() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        engine.createNodes(ticket,
                new ApprovalChain(List.of("LEAD", "DBA"), false, "L1_GLOBAL", RiskLevel.HIGH));

        // 节点 1（LEAD 占位）由 u2 认领决策：通过但还有节点 2，工单仍在审批
        ApprovalDecision first = engine.submitDecision(ticket.getId(), 2L, List.of("LEAD"), true, "lead ok");
        assertTrue(first.nodePassed());
        assertFalse(first.ticketApproved());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
        assertEquals(2L, approvalDao.listByTicket(ticket.getId()).get(0).getApproverId(),
                "ROLE 占位记录应被实际决策人认领");

        ApprovalDecision second = engine.submitDecision(ticket.getId(), 3L, List.of("DBA"), true, "dba ok");
        assertTrue(second.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 非当前节点决策_被拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        LocalDateTime now = LocalDateTime.now();
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.AND, now); // 节点 1：u2
        seedApproval(ticket.getId(), 2, 3L, ApprovalMode.AND, now); // 节点 2：u3

        // 节点 1 未过，u3 不能跳过流程决策节点 2
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), 3L, List.of(), true, "抢先"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
    }

    @Test
    void 非节点审批人决策_被拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.AND, LocalDateTime.now());

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), 99L, List.of(), true, "路人"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", ex.getCode());
    }

    @Test
    void 重复决策_同结果幂等_改结果抛异常() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        LocalDateTime now = LocalDateTime.now();
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.AND, now);
        seedApproval(ticket.getId(), 1, 3L, ApprovalMode.AND, now);

        engine.submitDecision(ticket.getId(), 2L, List.of(), true, "ok");
        // 同结果重复提交：幂等放行，节点仍未过
        ApprovalDecision again = engine.submitDecision(ticket.getId(), 2L, List.of(), true, "ok again");
        assertFalse(again.nodePassed());
        // 不同结果重复提交：拒绝
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), 2L, List.of(), false, "改主意"));
        assertEquals("APPROVAL_ALREADY_DECIDED", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
    }

    // ---- 审批 ≠ 执行：二次确认（§3.4） ----

    @Test
    void 二次确认_CRITICAL链全过只到approved_需显式确认才可执行() {
        Ticket ticket = newAuditingTicket(RiskLevel.CRITICAL);
        engine.createNodes(ticket, new ApprovalChain(
                List.of("LEAD", "DBA", "ADMIN"), true, "L1_GLOBAL", RiskLevel.CRITICAL));

        // 确认前置：未 approved 不能二次确认
        assertThrows(IllegalStateTransitionException.class,
                () -> engine.confirmExecution(ticket.getId(), 9L, List.of()));

        engine.submitDecision(ticket.getId(), 2L, List.of("LEAD"), true, "lead");   // 节点 1
        engine.submitDecision(ticket.getId(), 3L, List.of("DBA"), true, "dba");    // 节点 2
        engine.submitDecision(ticket.getId(), 4L, List.of("ADMIN"), true, "admin");  // 节点 3 → 全过

        // 全部节点通过只到 approved，审批 ≠ 执行
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
        assertTrue(engine.needsSecondConfirm(ticket.getId()));
        assertFalse(engine.canExecute(ticket.getId()), "二次确认前不得执行");

        // 提交人不得确认自己的工单
        SqlauditException self = assertThrows(SqlauditException.class,
                () -> engine.confirmExecution(ticket.getId(), CREATOR, List.of()));
        assertEquals("SELF_CONFIRM_FORBIDDEN", self.getCode());

        // DBA 显式二次确认后才可执行
        engine.confirmExecution(ticket.getId(), 3L, List.of("DBA"));
        assertTrue(engine.canExecute(ticket.getId()));
    }

    @Test
    void 二次确认_无secondConfirm链_直接可执行() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH);
        engine.createNodes(ticket,
                new ApprovalChain(List.of("LEAD"), false, "L1_GLOBAL", RiskLevel.HIGH));
        engine.submitDecision(ticket.getId(), 2L, List.of("LEAD"), true, "ok");

        assertFalse(engine.needsSecondConfirm(ticket.getId()));
        assertTrue(engine.canExecute(ticket.getId()));
    }

    // ---- 审批转交（代理人，§8.2 TRANSFERRED） ----

    @Test
    void 转交_把本人待审批记录转给代理人() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM);
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.OR, LocalDateTime.now());

        engine.transfer(ticket.getId(), 2L, 3L, List.of("LEAD"));

        Approval a = approvalDao.listByTicket(ticket.getId()).get(0);
        assertEquals(3L, a.getApproverId(), "待审批记录应转给代理人");
        assertEquals(ApprovalResult.PENDING, a.getResult(), "转交后仍为待审批");
    }

    @Test
    void 转交_不能转给提交人() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM);
        seedApproval(ticket.getId(), 1, 2L, ApprovalMode.OR, LocalDateTime.now());

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), 2L, CREATOR, List.of("LEAD")));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // ---- 超时提醒查询 ----

    @Test
    void listPendingOverdue_只返回审批中工单的当前节点超时记录() {
        LocalDateTime now = LocalDateTime.now();

        // 工单 A：auditing，节点 1 / 2 都有 2 小时前的 PENDING → 只提醒节点 1
        Ticket a = newAuditingTicket(RiskLevel.HIGH);
        seedApproval(a.getId(), 1, 2L, ApprovalMode.AND, now.minusHours(2));
        seedApproval(a.getId(), 2, 3L, ApprovalMode.AND, now.minusHours(2));

        // 工单 B：auditing，PENDING 但未超时 → 不提醒
        Ticket b = newAuditingTicket(RiskLevel.HIGH);
        seedApproval(b.getId(), 1, 4L, ApprovalMode.AND, now.minusMinutes(5));

        // 工单 C：已驳回，遗留的节点 PENDING → 不提醒
        Ticket c = newAuditingTicket(RiskLevel.HIGH);
        seedApproval(c.getId(), 1, 5L, ApprovalMode.AND, now.minusHours(2));
        seedApproval(c.getId(), 2, 6L, ApprovalMode.AND, now.minusHours(2));
        ticketDao.updateStatus(c.getId(), TicketStatus.AUDITING, TicketStatus.REJECTED, now);

        List<Approval> overdue = engine.listPendingOverdue(Instant.now(), Duration.ofHours(1));

        assertEquals(1, overdue.size());
        assertEquals(a.getId(), overdue.get(0).getTicketId());
        assertEquals(1, overdue.get(0).getNodeOrder());
        assertEquals(2L, overdue.get(0).getApproverId());
    }
}
