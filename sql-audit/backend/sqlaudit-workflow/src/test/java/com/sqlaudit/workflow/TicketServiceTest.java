package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.rule.risk.ChainNode;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeInstanceDao;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeRoleDao;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * {@link TicketService} 单测（ERROR 阻断 / 单实例路由 /
 * 非 LOW 空链拒绝 / LOW 免审 / approved 超时 expired / 取消）。
 */
class TicketServiceTest {

    private static final long CREATOR = 1L;
    private static final long LEAD_USER = 2L;

    private final FakeUserDao users = new FakeUserDao();
    private final FakeRoleDao roles = new FakeRoleDao(users);
    private final FakeInstanceDao instances = new FakeInstanceDao();
    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    private final InMemoryTicketUnitDao unitDao = new InMemoryTicketUnitDao();
    private final InMemoryApprovalDao approvalDao = new InMemoryApprovalDao();
    private final TicketStateMachine stateMachine = new TicketStateMachine(ticketDao);
    private final ApprovalFlowEngine approvalEngine =
            new ApprovalFlowEngine(approvalDao, ticketDao, users, roles, instances, stateMachine);
    /** LOW 空链免审 / MEDIUM = LEAD / HIGH = LEAD→DBA / CRITICAL = LEAD→DBA→ADMIN（种子形状）。 */
    private final FlowConfig flowConfig = new FlowConfig();
    private final TicketService service = new TicketService(
            ticketDao, unitDao::insert, stateMachine, approvalEngine,
            new ApprovalRouter(flowConfig));

    @BeforeEach
    void seed() {
        users.add(CREATOR, "creator", UserStatus.ACTIVE);
        users.add(LEAD_USER, "lead", UserStatus.ACTIVE);
        users.add(3L, "dba", UserStatus.ACTIVE);
        users.add(4L, "admin", UserStatus.ACTIVE);
        roles.addRole("LEAD");
        roles.addMember("LEAD", LEAD_USER);
        roles.addRole("DBA");
        roles.addMember("DBA", 3L);
        roles.addRole("ADMIN");
        roles.addMember("ADMIN", 4L);
        instances.add(1L, 4L);

        flowConfig.putGlobal(RiskLevel.LOW, List.of());
        flowConfig.putGlobal(RiskLevel.MEDIUM, List.of(roleNode(1, 21, "LEAD")));
        flowConfig.putGlobal(RiskLevel.HIGH,
                List.of(roleNode(1, 22, "LEAD"), roleNode(2, 23, "DBA")));
        flowConfig.putGlobal(RiskLevel.CRITICAL,
                List.of(roleNode(1, 24, "LEAD"), roleNode(2, 25, "DBA"),
                        roleNode(3, 26, "ADMIN")));
    }

    private static ChainNode roleNode(int order, long flowNodeId, String roleCode) {
        return new ChainNode(order, flowNodeId, ApproverType.ROLE, roleCode,
                com.sqlaudit.common.enums.ApprovalMode.OR);
    }

    // ---- 造数 ----

    private TicketUnit unit(String sql) {
        TicketUnit unit = new TicketUnit();
        unit.setInstanceId(1L);
        unit.setSchemaName("app");
        unit.setObjectName("t_user");
        unit.setStatement(sql);
        unit.setStatementHash("hash-" + sql.hashCode());
        unit.setStatementType(StatementType.UPDATE);
        return unit;
    }

    private long createTicket(RiskLevel riskLevel, long instanceId) {
        Ticket ticket = service.createTicket("标题", CREATOR, instanceId, riskLevel,
                "{\"rollbackable\":[]}", null, null, List.of(unit("UPDATE t_user SET name='a' WHERE id=1")));
        return ticket.getId();
    }

    private AuditOutcome cleanOutcome() {
        return new AuditOutcome(Map.of(), false, null);
    }

    private AuditOutcome errorBlockedOutcome() {
        RuleHit noWhere = new RuleHit(RuleCode.R_NO_WHERE, RuleSeverity.ERROR, "UPDATE/DELETE 无 WHERE");
        return new AuditOutcome(Map.of(0, List.of(noWhere)), true, RuleSeverity.ERROR);
    }

    private TicketStatus statusOf(long ticketId) {
        return service.getTicket(ticketId).getStatus();
    }

    // ---- createTicket ----

    @Test
    void createTicket_草稿工单与units落库_单实例() {
        TicketUnit u1 = unit("ALTER TABLE t ADD c INT");
        TicketUnit u2 = unit("UPDATE t SET c=1 WHERE id=1");
        Ticket ticket = service.createTicket("加列", CREATOR, 1L, RiskLevel.HIGH,
                "{\"rollbackable\":[\"unit1\"]}", "ALTER TABLE t ADD c INT", null, List.of(u1, u2));

        assertEquals(TicketStatus.DRAFT, ticket.getStatus());
        assertNotNull(ticket.getTraceId());
        assertEquals("{\"rollbackable\":[\"unit1\"]}", ticket.getRollbackSummary());
        assertEquals("ALTER TABLE t ADD c INT", ticket.getSqlText());
        assertEquals(RiskLevel.HIGH, ticket.getRiskLevel());
        assertEquals(1L, ticket.getInstanceId(), "单实例工单");

        // units 落库：ticketId 回填、unit_order 1..n、创建态 pending（README §4.2）
        assertEquals(ticket.getId(), u1.getTicketId());
        assertEquals(1, u1.getUnitOrder());
        assertEquals(2, u2.getUnitOrder());
        assertEquals(2, unitDao.countByStatus(UnitStatus.PENDING));
    }

    @Test
    void createTicket_无语句抛异常() {
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.createTicket("空", CREATOR, 1L, RiskLevel.LOW, null,
                        null, null, List.of()));
        assertEquals("EMPTY_UNITS", ex.getCode());
    }

    // ---- submitForAudit：ERROR 阻断 ----

    @Test
    void submitForAudit_ERROR阻断拒绝提交且停留草稿() {
        long ticketId = createTicket(RiskLevel.HIGH, 1L);

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.submitForAudit(ticketId, errorBlockedOutcome()));
        assertEquals("ERROR_RULE_BLOCKED", ex.getCode());
        assertEquals(TicketStatus.DRAFT, statusOf(ticketId), "ERROR 命中的工单必须停留 draft");
    }

    @Test
    void submitForAudit_干净工单进入auditing() {
        long ticketId = createTicket(RiskLevel.HIGH, 1L);
        service.submitForAudit(ticketId, cleanOutcome());
        assertEquals(TicketStatus.AUDITING, statusOf(ticketId));
    }

    // ---- submitForApproval：auditing 态生成审批节点（两步合并后由提交链路直接调用） ----

    @Test
    void submitForApproval_非auditing态抛非法迁移异常() {
        long ticketId = createTicket(RiskLevel.HIGH, 1L); // draft
        assertThrows(IllegalStateTransitionException.class,
                () -> service.submitForApproval(ticketId));
    }

    @Test
    void submitForApproval_HIGH链生成两级节点_依次审批通过() {
        long ticketId = createTicket(RiskLevel.HIGH, 1L);
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId);

        List<Approval> nodes = approvalDao.listByTicket(ticketId);
        assertEquals(2, nodes.size(), "HIGH 链 = LEAD + DBA 两组");
        assertEquals(TicketStatus.AUDITING, statusOf(ticketId), "审批期间工单保持 auditing");

        // 两级顺序审批 → approved
        approvalEngine.submitDecision(ticketId, LEAD_USER, true, "lead ok");
        approvalEngine.submitDecision(ticketId, 3L, true, "dba ok");
        assertEquals(TicketStatus.APPROVED, statusOf(ticketId));
    }

    @Test
    void submitForApproval_LOW空链自动通过留痕() {
        long ticketId = createTicket(RiskLevel.LOW, 1L);
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId);

        assertEquals(TicketStatus.APPROVED, statusOf(ticketId), "LOW 空链自动通过（README §4.3）");
        assertTrue(approvalDao.listByTicket(ticketId).isEmpty(), "空链无审批节点");
    }

    // ---- 非 LOW 空链拒绝（决定 9：不再自动批准） ----

    @Test
    void submitForApproval_非LOW无配置审批流_拒绝提交() {
        flowConfig.putGlobal(RiskLevel.HIGH, List.of()); // 管理员删光了 HIGH 全局链
        long ticketId = createTicket(RiskLevel.HIGH, 1L);
        service.submitForAudit(ticketId, cleanOutcome());

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> service.submitForApproval(ticketId));
        assertEquals("APPROVAL_FLOW_NOT_CONFIGURED", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticketId), "拒绝后工单停留在 auditing");
        assertTrue(approvalDao.listByTicket(ticketId).isEmpty(), "不得生成审批节点");
    }

    @Test
    void submitForApproval_LOW配置了节点且全被跳过_自动通过留痕() {
        // LOW 配了提交人本人的节点：全部跳过 → 自动 approved（留痕 SKIPPED 记录）
        flowConfig.putGlobal(RiskLevel.LOW, List.of(new ChainNode(
                1, 27, ApproverType.USER, String.valueOf(CREATOR),
                com.sqlaudit.common.enums.ApprovalMode.OR)));
        long ticketId = createTicket(RiskLevel.LOW, 1L);
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId);

        assertEquals(TicketStatus.APPROVED, statusOf(ticketId));
        List<Approval> rows = approvalDao.listByTicket(ticketId);
        assertEquals(1, rows.size());
        assertEquals(ApprovalResult.SKIPPED, rows.get(0).getResult(), "跳过的节点留痕");
    }

    // ---- 实例追加链（决定 9：实例组接在全局组之后） ----

    @Test
    void submitForApproval_实例专用链追加在全局链后() {
        // 实例 7 的 HIGH 专用链：再加一组 USER 王五 → 生效链 = LEAD(1) + DBA(2) + 王五(3)
        users.add(5L, "wangwu", UserStatus.ACTIVE);
        flowConfig.putInstance(7, RiskLevel.HIGH, List.of(new ChainNode(
                1, 31, ApproverType.USER, "5", com.sqlaudit.common.enums.ApprovalMode.OR)));
        long ticketId = createTicket(RiskLevel.HIGH, 7L);
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId);

        List<Approval> nodes = approvalDao.listByTicket(ticketId);
        assertEquals(3, nodes.size(), "生效链 = 全局 LEAD+DBA + 实例 王五");
        assertEquals(3, nodes.get(2).getNodeOrder(), "实例组 node_order 接在全局组之后");
        assertEquals("5", nodes.get(2).getApproverRef());

        approvalEngine.submitDecision(ticketId, LEAD_USER, true, "lead");
        approvalEngine.submitDecision(ticketId, 3L, true, "dba");
        assertEquals(TicketStatus.AUDITING, statusOf(ticketId), "实例组的王五未审批，不得执行");
        approvalEngine.submitDecision(ticketId, 5L, true, "wangwu");
        assertEquals(TicketStatus.APPROVED, statusOf(ticketId));
    }

    // ---- cancel / checkExpiry ----

    @Test
    void cancel_非终态可取消_终态抛非法迁移异常() {
        long draft = createTicket(RiskLevel.HIGH, 1L);
        service.cancel(draft, "u1");
        assertEquals(TicketStatus.CANCELLED, statusOf(draft));

        long auditing = createTicket(RiskLevel.HIGH, 1L);
        service.submitForAudit(auditing, cleanOutcome());
        service.cancel(auditing, "u1");
        assertEquals(TicketStatus.CANCELLED, statusOf(auditing));

        // 终态再取消 → 状态机非法迁移异常（不是静默忽略）
        assertThrows(IllegalStateTransitionException.class, () -> service.cancel(draft, "u1"));
    }

    @Test
    void checkExpiry_approved超时未执行转expired_未超时保持() {
        long ticketId = createTicket(RiskLevel.LOW, 1L);
        service.submitForAudit(ticketId, cleanOutcome());
        service.submitForApproval(ticketId); // LOW → approved
        assertEquals(TicketStatus.APPROVED, statusOf(ticketId));

        // 未超时：保持 approved
        assertFalse(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(1)), Duration.ofHours(24)));
        assertEquals(TicketStatus.APPROVED, statusOf(ticketId));

        // 超过 approval_ttl（README §4.2 默认 24h）→ expired（需重新审批，走新工单）
        assertTrue(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(25)), Duration.ofHours(24)));
        assertEquals(TicketStatus.EXPIRED, statusOf(ticketId));
    }

    @Test
    void checkExpiry_非approved态直接返回false() {
        long ticketId = createTicket(RiskLevel.HIGH, 1L);
        assertFalse(service.checkExpiry(ticketId, Instant.now().plus(Duration.ofHours(48)), Duration.ofHours(24)));
        assertEquals(TicketStatus.DRAFT, statusOf(ticketId));
    }

    // ---- 监听器（W2 接 AuditWriter 的审计钩子） ----

    @Test
    void 审计钩子_服务层状态变更触发监听器() {
        List<String> events = new java.util.ArrayList<>();
        TicketStateMachine sm = new TicketStateMachine(ticketDao,
                (tid, from, to, actor) -> events.add(from + ">" + to + ":" + actor));
        ApprovalFlowEngine engine = new ApprovalFlowEngine(approvalDao, ticketDao, users, roles,
                instances, sm);
        TicketService svc = new TicketService(ticketDao, unitDao::insert, sm, engine,
                new ApprovalRouter(flowConfig));

        long ticketId = svc.createTicket("审计", CREATOR, 1L, RiskLevel.MEDIUM,
                null, null, null, List.of(unit("UPDATE t SET a=1 WHERE id=1"))).getId();
        svc.submitForAudit(ticketId, cleanOutcome());

        assertEquals(List.of("DRAFT>SUBMITTED:1", "SUBMITTED>AUDITING:1"), events);
    }
}
