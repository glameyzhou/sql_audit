package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TicketService} 单测（B-3 验收：ERROR 阻断 / 影响面评估校验 / L1 兜底 /
 * approved 超时 expired / 取消）。
 */
class TicketServiceTest {

    private static final long CREATOR = 1L;

    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    private final InMemoryTicketUnitDao unitDao = new InMemoryTicketUnitDao();
    private final InMemoryApprovalDao approvalDao = new InMemoryApprovalDao();
    private final TicketStateMachine stateMachine = new TicketStateMachine(ticketDao);
    private final ApprovalFlowEngine approvalEngine =
            new ApprovalFlowEngine(approvalDao, ticketDao, stateMachine);
    private final TicketService service = new TicketService(
            ticketDao, unitDao::insert, stateMachine, approvalEngine,
            new ApprovalRouter(new FlowConfig()));

    // ---- 造数 ----

    private TicketUnit unit(String sql) {
        TicketUnit unit = new TicketUnit();
        unit.setInstanceId(1L);
        unit.setSchemaName("app");
        unit.setObjectName("t_user");
        unit.setStatement(sql.getBytes(StandardCharsets.UTF_8));
        unit.setStatementHash("hash-" + sql.hashCode());
        unit.setStatementType("UPDATE");
        return unit;
    }

    private long createTicket(RiskLevel riskLevel, List<Long> instanceIds) {
        Ticket ticket = service.createTicket("标题", CREATOR, instanceIds, "prod", riskLevel,
                "{\"rollbackable\":[]}", List.of(unit("UPDATE t_user SET name='a' WHERE id=1")));
        return ticket.getId();
    }

    private AuditOutcome cleanOutcome() {
        return new AuditOutcome(Map.of(), false, null);
    }

    private AuditOutcome errorBlockedOutcome() {
        RuleHit noWhere = new RuleHit("R_NO_WHERE", RuleSeverity.ERROR, "UPDATE/DELETE 无 WHERE");
        return new AuditOutcome(Map.of(0, List.of(noWhere)), true, RuleSeverity.ERROR);
    }

    private String statusOf(long ticketId) {
        return service.getTicket(ticketId).getStatus();
    }

    // ---- createTicket ----

    @Test
    void createTicket_草稿工单与units落库() {
        TicketUnit u1 = unit("ALTER TABLE t ADD c INT");
        TicketUnit u2 = unit("UPDATE t SET c=1 WHERE id=1");
        Ticket ticket = service.createTicket("加列", CREATOR, List.of(1L), "prod", RiskLevel.HIGH,
                "{\"rollbackable\":[\"unit1\"]}", List.of(u1, u2));

        assertEquals("draft", ticket.getStatus());
        assertNotNull(ticket.getTraceId());
        assertEquals("{\"rollbackable\":[\"unit1\"]}", ticket.getRollbackSummary());
        assertEquals(RiskLevel.HIGH.name(), ticket.getRiskLevel());

        // units 落库：ticketId 回填、unit_order 1..n、创建态 pending（§3.1）
        assertEquals(ticket.getId(), u1.getTicketId());
        assertEquals(1, u1.getUnitOrder());
        assertEquals(2, u2.getUnitOrder());
        assertEquals(2, unitDao.countByStatus("pending"));
    }

    @Test
    void createTicket_无语句抛异常() {
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.createTicket("空", CREATOR, List.of(1L), "prod", RiskLevel.LOW, null, List.of()));
        assertEquals("EMPTY_UNITS", ex.getCode());
    }

    // ---- submitForAudit：ERROR 阻断 ----

    @Test
    void submitForAudit_ERROR阻断拒绝提交且停留草稿() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.submitForAudit(ticketId, errorBlockedOutcome()));
        assertEquals("ERROR_RULE_BLOCKED", ex.getCode());
        assertEquals("draft", statusOf(ticketId), "ERROR 命中的工单必须停留 draft");
    }

    @Test
    void submitForAudit_干净工单进入auditing() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L));
        service.submitForAudit(ticketId, cleanOutcome());
        assertEquals("auditing", statusOf(ticketId));
    }

    // ---- submitForApproval：影响面评估（§4.4 验收硬性要求） ----

    @Test
    void submitForApproval_影响面评估未完成抛异常且不生成节点() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L));
        service.submitForAudit(ticketId, cleanOutcome());

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.submitForApproval(ticketId, false));
        assertEquals("IMPACT_ASSESSMENT_INCOMPLETE", ex.getCode());
        assertTrue(approvalDao.listByTicket(ticketId).isEmpty(), "评估未完成不得生成审批节点");
    }

    @Test
    void submitForApproval_非auditing态抛非法迁移异常() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L)); // draft
        assertThrows(IllegalStateTransitionException.class,
                () -> service.submitForApproval(ticketId, true));
    }

    @Test
    void submitForApproval_HIGH链生成两级会签节点() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L));
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId, true);

        List<Approval> nodes = approvalDao.listByTicket(ticketId);
        assertEquals(2, nodes.size(), "HIGH L1 链 = 主管 + DBA");
        assertEquals("auditing", statusOf(ticketId), "审批期间工单保持 auditing");

        // 两级顺序审批 → approved
        approvalEngine.submitDecision(ticketId, 2L, true, "lead ok");
        approvalEngine.submitDecision(ticketId, 3L, true, "dba ok");
        assertEquals("approved", statusOf(ticketId));
    }

    @Test
    void submitForApproval_LOW空链自动通过留痕() {
        long ticketId = createTicket(RiskLevel.LOW, List.of(1L));
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId, true);

        assertEquals("approved", statusOf(ticketId), "LOW 自动通过（§4.5）");
        assertTrue(approvalDao.listByTicket(ticketId).isEmpty(), "空链无审批节点");
    }

    // ---- L1 兜底（§4.5：新实例漏配绝不无审批） ----

    @Test
    void 新实例漏配审批流_路由落L1全局兜底链而非无审批() {
        ApprovalRouter router = new ApprovalRouter(new FlowConfig());
        ApprovalChain chain = router.route(RiskLevel.MEDIUM, 999L, null);

        assertFalse(chain.nodes().isEmpty(), "漏配实例必须落 L1 全局链，绝不无审批");
        assertEquals("L1_GLOBAL", chain.source());
        assertTrue(chain.atLeastAsStrictAs(new ApprovalChain(List.of("LEAD"), false, "L1_GLOBAL", RiskLevel.MEDIUM)));

        // workflow 端到端：漏配实例（999 无任何 flow 配置）的工单照常生成审批节点
        long ticketId = createTicket(RiskLevel.MEDIUM, List.of(999L));
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId, true);

        assertEquals(1, approvalDao.listByTicket(ticketId).size(), "MEDIUM L1 链 = 主管 1 人");
        approvalEngine.submitDecision(ticketId, 2L, true, "ok");
        assertEquals("approved", statusOf(ticketId));
    }

    // ---- cancel / checkExpiry ----

    @Test
    void cancel_非终态可取消_终态抛非法迁移异常() {
        long draft = createTicket(RiskLevel.HIGH, List.of(1L));
        service.cancel(draft, "u1");
        assertEquals("cancelled", statusOf(draft));

        long auditing = createTicket(RiskLevel.HIGH, List.of(1L));
        service.submitForAudit(auditing, cleanOutcome());
        service.cancel(auditing, "u1");
        assertEquals("cancelled", statusOf(auditing));

        // 终态再取消 → 状态机非法迁移异常（不是静默忽略）
        assertThrows(IllegalStateTransitionException.class, () -> service.cancel(draft, "u1"));
    }

    @Test
    void checkExpiry_approved超时未执行转expired_未超时保持() {
        long ticketId = createTicket(RiskLevel.LOW, List.of(1L));
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId, true); // LOW → approved
        assertEquals("approved", statusOf(ticketId));

        // 未超时：保持 approved
        assertFalse(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(1)), Duration.ofHours(24)));
        assertEquals("approved", statusOf(ticketId));

        // 超过 approval_ttl（§3.5 默认 24h）→ expired（需重新审批，走新工单）
        assertTrue(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(25)), Duration.ofHours(24)));
        assertEquals("expired", statusOf(ticketId));
    }

    @Test
    void checkExpiry_非approved态直接返回false() {
        long ticketId = createTicket(RiskLevel.HIGH, List.of(1L));
        assertFalse(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(48)), Duration.ofHours(24)));
        assertEquals("draft", statusOf(ticketId));
    }

    // ---- 监听器（W2 接 AuditWriter 的审计钩子） ----

    @Test
    void 审计钩子_服务层状态变更触发监听器() {
        List<String> events = new java.util.ArrayList<>();
        TicketStateMachine sm = new TicketStateMachine(ticketDao,
                (tid, from, to, actor) -> events.add(from + ">" + to + ":" + actor));
        ApprovalFlowEngine engine = new ApprovalFlowEngine(approvalDao, ticketDao, sm);
        TicketService svc = new TicketService(ticketDao, unitDao::insert, sm, engine,
                new ApprovalRouter(new FlowConfig()));

        long ticketId = svc.createTicket("审计", CREATOR, List.of(1L), "prod", RiskLevel.MEDIUM,
                null, List.of(unit("UPDATE t SET a=1 WHERE id=1"))).getId();
        svc.submitForAudit(ticketId, cleanOutcome());

        assertEquals(List.of("DRAFT>SUBMITTED:1", "SUBMITTED>AUDITING:1"), events);
    }
}
