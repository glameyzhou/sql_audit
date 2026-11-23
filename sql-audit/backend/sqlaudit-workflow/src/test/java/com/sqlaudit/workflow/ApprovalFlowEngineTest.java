package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.ChainNode;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeInstanceDao;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeRoleDao;
import com.sqlaudit.workflow.WorkflowTestDoubles.FakeUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalFlowEngine} 单测（决定 9 追加模式：USER / ROLE / INSTANCE_OWNER 解析、
 * 防自审跳过留痕、会签 / 或签、转交、二次确认、并发重复生成防护）。
 */
class ApprovalFlowEngineTest {

    private static final long CREATOR = 1L;
    private static final long ZHANG = 100L;   // 张三
    private static final long LI = 200L;      // 李四
    private static final long WANG = 300L;    // 王五

    private final FakeUserDao users = new FakeUserDao();
    private final FakeRoleDao roles = new FakeRoleDao(users);
    private final FakeInstanceDao instances = new FakeInstanceDao();
    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    private final InMemoryApprovalDao approvalDao = new InMemoryApprovalDao();
    private final ApprovalFlowEngine engine = new ApprovalFlowEngine(
            approvalDao, ticketDao, users, roles, instances,
            new TicketStateMachine(ticketDao));

    @BeforeEach
    void seedPeople() {
        users.add(CREATOR, "creator", UserStatus.ACTIVE);
        users.add(ZHANG, "zhangsan", UserStatus.ACTIVE);
        users.add(LI, "lisi", UserStatus.ACTIVE);
        users.add(WANG, "wangwu", UserStatus.ACTIVE);
    }

    // ---- 造数 ----

    private Ticket newAuditingTicket(RiskLevel riskLevel, long instanceId, long creatorId) {
        Ticket ticket = new Ticket();
        ticket.setTitle("approval-test");
        ticket.setCreatorId(creatorId);
        ticket.setInstanceId(instanceId);
        ticket.setRiskLevel(riskLevel);
        ticket.setStatus(TicketStatus.AUDITING);
        ticket.setTraceId("tr-" + System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        long id = ticketDao.insertTicket(ticket);
        return ticketDao.findById(id);
    }

    private static ChainNode user(int order, long flowNodeId, long userId) {
        return new ChainNode(order, flowNodeId, ApproverType.USER, String.valueOf(userId),
                ApprovalMode.OR);
    }

    private static ChainNode userAnd(int order, long flowNodeId, long userId) {
        return new ChainNode(order, flowNodeId, ApproverType.USER, String.valueOf(userId),
                ApprovalMode.AND);
    }

    private static ChainNode role(int order, long flowNodeId, String roleCode) {
        return new ChainNode(order, flowNodeId, ApproverType.ROLE, roleCode, ApprovalMode.OR);
    }

    private static ChainNode owner(int order, long flowNodeId) {
        return new ChainNode(order, flowNodeId, ApproverType.INSTANCE_OWNER, "*", ApprovalMode.OR);
    }

    private TicketStatus statusOf(Ticket ticket) {
        return ticketDao.findById(ticket.getId()).getStatus();
    }

    // ---------------- 节点解析（试解析） ----------------

    @Test
    void resolve_非LOW空链_拒绝提交() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH, 1L, CREATOR);
        ApprovalChain chain = new ApprovalChain(List.of(), false, RiskLevel.HIGH);

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.resolveNodes(ticket, chain));
        assertEquals("APPROVAL_FLOW_NOT_CONFIGURED", ex.getCode(),
                "非 LOW 生效链为空必须拒绝，不再自动批准");
    }

    @Test
    void resolve_LOW空链_免审() {
        Ticket ticket = newAuditingTicket(RiskLevel.LOW, 1L, CREATOR);
        assertTrue(engine.resolveNodes(ticket,
                new ApprovalChain(List.of(), false, RiskLevel.LOW)).isEmpty());
    }

    @Test
    void resolve_USER节点_生成本人可审记录() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        List<Approval> rows = engine.resolveNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        assertEquals(1, rows.size());
        Approval row = rows.get(0);
        assertEquals(ApproverType.USER, row.getApproverType());
        assertEquals("100", row.getApproverRef());
        assertEquals(ApprovalResult.PENDING, row.getResult());
        assertEquals(11L, row.getFlowNodeId(), "flowNodeId 保留真实来源");
        assertEquals(1, row.getNodeOrder());
    }

    @Test
    void resolve_USER节点是提交人_SKIPPED留痕() {
        Ticket ticket = newAuditingTicket(RiskLevel.LOW, 1L, CREATOR);
        List<Approval> rows = engine.resolveNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, CREATOR)), false, RiskLevel.LOW));

        assertEquals(1, rows.size());
        assertEquals(ApprovalResult.SKIPPED, rows.get(0).getResult(), "提交人节点必须被跳过");
        assertEquals(CREATOR, rows.get(0).getDecidedBy(), "跳过留痕 decided_by = 提交人");
    }

    @Test
    void resolve_非LOW全部跳过_NO_ELIGIBLE_APPROVER() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        SqlauditException ex = assertThrows(SqlauditException.class, () -> engine.resolveNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, CREATOR)), false, RiskLevel.MEDIUM)));
        assertEquals("NO_ELIGIBLE_APPROVER", ex.getCode(), "无可审人（全跳过）且非 LOW → 拒绝");
    }

    @Test
    void resolve_非LOW部分跳过_剩余节点可审() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        List<Approval> rows = engine.resolveNodes(ticket, new ApprovalChain(
                List.of(userAnd(1, 11, CREATOR), userAnd(1, 12, ZHANG)), false, RiskLevel.MEDIUM));

        assertEquals(2, rows.size());
        assertEquals(ApprovalResult.SKIPPED, rows.get(0).getResult());
        assertEquals(ApprovalResult.PENDING, rows.get(1).getResult());
    }

    @Test
    void resolve_ROLE节点无除提交人外成员_拒绝() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", CREATOR); // 唯一成员就是提交人
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);

        SqlauditException ex = assertThrows(SqlauditException.class, () -> engine.resolveNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM)));
        assertEquals("NO_ELIGIBLE_APPROVER", ex.getCode());
    }

    @Test
    void resolve_ROLE节点_保持角色记录待认领() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", ZHANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);

        List<Approval> rows = engine.resolveNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM));

        assertEquals(1, rows.size());
        assertEquals(ApproverType.ROLE, rows.get(0).getApproverType());
        assertEquals("LEAD", rows.get(0).getApproverRef());
        assertEquals(ApprovalResult.PENDING, rows.get(0).getResult());
    }

    @Test
    void resolve_ROLE节点引用不存在的角色_拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        SqlauditException ex = assertThrows(SqlauditException.class, () -> engine.resolveNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "GHOST")), false, RiskLevel.MEDIUM)));
        assertEquals("NO_ELIGIBLE_APPROVER", ex.getCode());
    }

    @Test
    void resolve_USER节点停用_拒绝() {
        users.setStatus(ZHANG, UserStatus.DISABLED);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);

        SqlauditException ex = assertThrows(SqlauditException.class, () -> engine.resolveNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM)));
        assertEquals("NO_ELIGIBLE_APPROVER", ex.getCode());
    }

    @Test
    void resolve_INSTANCE_OWNER_解析负责人为快照() {
        instances.add(1L, ZHANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);

        List<Approval> rows = engine.resolveNodes(ticket,
                new ApprovalChain(List.of(owner(1, 11)), false, RiskLevel.MEDIUM));

        assertEquals(1, rows.size());
        assertEquals(ApproverType.INSTANCE_OWNER, rows.get(0).getApproverType());
        assertEquals("100", rows.get(0).getApproverRef(), "提交时解析出的负责人用户 id 快照");
    }

    @Test
    void resolve_INSTANCE_OWNER负责人是提交人_SKIPPED() {
        instances.add(1L, CREATOR);
        Ticket ticket = newAuditingTicket(RiskLevel.LOW, 1L, CREATOR);

        List<Approval> rows = engine.resolveNodes(ticket,
                new ApprovalChain(List.of(owner(1, 11)), false, RiskLevel.LOW));

        assertEquals(1, rows.size());
        assertEquals(ApprovalResult.SKIPPED, rows.get(0).getResult());
        assertEquals(CREATOR, rows.get(0).getDecidedBy());
    }

    @Test
    void resolve_实例不存在_拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 999L, CREATOR);
        SqlauditException ex = assertThrows(SqlauditException.class, () -> engine.resolveNodes(ticket,
                new ApprovalChain(List.of(owner(1, 11)), false, RiskLevel.MEDIUM)));
        assertEquals("NO_ELIGIBLE_APPROVER", ex.getCode());
    }

    // ---------------- 节点生成 ----------------

    @Test
    void createNodes_生成记录并标记二次确认() {
        Ticket ticket = newAuditingTicket(RiskLevel.CRITICAL, 1L, CREATOR);
        ApprovalChain chain = new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(2, 12, LI)), true, RiskLevel.CRITICAL);

        List<Approval> nodes = engine.createNodes(ticket, chain);

        assertEquals(2, nodes.size());
        assertEquals(1, nodes.get(0).getNodeOrder());
        assertEquals(2, nodes.get(1).getNodeOrder());
        assertTrue(engine.needsSecondConfirm(ticket.getId()), "CRITICAL 链落二次确认标记");
    }

    @Test
    void createNodes_重复生成抛异常() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        ApprovalChain chain = new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM);
        engine.createNodes(ticket, chain);

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.createNodes(ticket, chain));
        assertEquals("APPROVAL_NODES_EXIST", ex.getCode());
    }

    @Test
    void createNodes_插入撞唯一键_视为并发生成失败() {
        // 并发重复生成：DAO 撞 uk_approval_assignee 返回 0 → 引擎必须视为失败（不当作成功）
        ApprovalDao failingDao = new ApprovalDao() {
            private final InMemoryApprovalDao delegate = new InMemoryApprovalDao();
            private boolean failNext = true;

            @Override
            public long insert(Approval approval) {
                if (failNext) {
                    failNext = false;
                    return 0L; // 模拟唯一键冲突
                }
                return delegate.insert(approval);
            }

            @Override
            public List<Approval> listByTicket(long ticketId) {
                return delegate.listByTicket(ticketId);
            }

            @Override
            public int updateResult(long id, long decidedBy, ApprovalResult fromResult,
                                    ApprovalResult toResult, String comment, LocalDateTime decidedAt) {
                return delegate.updateResult(id, decidedBy, fromResult, toResult, comment, decidedAt);
            }

            @Override
            public List<Approval> listPending() {
                return delegate.listPending();
            }

            @Override
            public long transfer(long id, long fromUserId, long toUserId, LocalDateTime transferredAt) {
                return delegate.transfer(id, fromUserId, toUserId, transferredAt);
            }
        };
        ApprovalFlowEngine racing = new ApprovalFlowEngine(
                failingDao, ticketDao, users, roles, instances, new TicketStateMachine(ticketDao));
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> racing.createNodes(ticket,
                        new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM)));
        assertEquals("APPROVAL_NODES_EXIST", ex.getCode());
    }

    // ---------------- 防自审（README §4.6 硬校验） ----------------

    @Test
    void 防自审_提交人审批自己的工单必须被拒() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), CREATOR, true, "自己批自己"));
        assertEquals("SELF_APPROVAL_FORBIDDEN", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
        assertEquals(ApprovalResult.PENDING, approvalDao.listByTicket(ticket.getId()).get(0).getResult());
    }

    // ---------------- 张三 → 李四 指定人流程（决定 3 的验收形态） ----------------

    @Test
    void 张三李四流程_依次审批通过() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(2, 12, LI)), false, RiskLevel.MEDIUM));

        // 李四不能抢在张三前决策
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), LI, true, "抢先"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", ex.getCode(), "只能作用于当前组");

        ApprovalDecision first = engine.submitDecision(ticket.getId(), ZHANG, true, "张三同意");
        assertTrue(first.nodePassed());
        assertFalse(first.ticketApproved(), "还有李四的节点");
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));

        ApprovalDecision second = engine.submitDecision(ticket.getId(), LI, true, "李四同意");
        assertTrue(second.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 张三提交_自己的节点被跳过留痕_李四审批通过() {
        // 张三本人提交 → 张三节点 SKIPPED 留痕，直接轮到李四
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, ZHANG);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(2, 12, LI)), false, RiskLevel.MEDIUM));

        List<Approval> rows = approvalDao.listByTicket(ticket.getId());
        assertEquals(2, rows.size());
        assertEquals(ApprovalResult.SKIPPED, rows.get(0).getResult(), "提交人节点跳过留痕");
        assertEquals(ZHANG, rows.get(0).getDecidedBy());

        // 张三不能自审（已被跳过，也不允许认领）
        SqlauditException self = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), ZHANG, true, "自己批自己"));
        assertEquals("SELF_APPROVAL_FORBIDDEN", self.getCode());

        ApprovalDecision li = engine.submitDecision(ticket.getId(), LI, true, "李四同意");
        assertTrue(li.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    // ---------------- 会签 / 或签 ----------------

    @Test
    void 会签_AND_两审批人全过才过节点并转approved() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(userAnd(1, 11, ZHANG), userAnd(1, 12, LI)), false, RiskLevel.HIGH));

        ApprovalDecision first = engine.submitDecision(ticket.getId(), ZHANG, true, "ok");
        assertFalse(first.nodePassed());
        assertFalse(first.ticketApproved());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));

        ApprovalDecision second = engine.submitDecision(ticket.getId(), LI, true, "ok");
        assertTrue(second.nodePassed());
        assertTrue(second.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 会签_AND_一人驳回即驳回工单() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(userAnd(1, 11, ZHANG), userAnd(1, 12, LI)), false, RiskLevel.HIGH));

        engine.submitDecision(ticket.getId(), ZHANG, true, "ok");
        ApprovalDecision reject = engine.submitDecision(ticket.getId(), LI, false, "风险太高");

        assertTrue(reject.ticketRejected());
        assertEquals(TicketStatus.REJECTED, statusOf(ticket));
    }

    @Test
    void 或签_OR_一人通过即过节点() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(1, 12, LI)), false, RiskLevel.MEDIUM));

        ApprovalDecision decision = engine.submitDecision(ticket.getId(), ZHANG, true, "ok");
        assertTrue(decision.nodePassed());
        assertTrue(decision.ticketApproved());
        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
    }

    @Test
    void 或签_OR_一人驳回且无人通过即驳回() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(1, 12, LI)), false, RiskLevel.MEDIUM));

        ApprovalDecision decision = engine.submitDecision(ticket.getId(), ZHANG, false, "不批");
        assertTrue(decision.ticketRejected());
        assertEquals(TicketStatus.REJECTED, statusOf(ticket));
    }

    // ---------------- ROLE 节点：认领与角色校验 ----------------

    @Test
    void ROLE节点_持有角色者认领_非成员被拒() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", ZHANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM));

        // 非角色成员不能认领
        SqlauditException denied = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), WANG, true, "路人"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", denied.getCode());

        ApprovalDecision ok = engine.submitDecision(ticket.getId(), ZHANG, true, "主管同意");
        assertTrue(ok.ticketApproved());
        assertEquals(ZHANG, approvalDao.listByTicket(ticket.getId()).get(0).getDecidedBy(),
                "认领时写入实际决策人 decided_by");
    }

    @Test
    void ROLE节点_提交人持有角色也不能自审() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", CREATOR);
        roles.addMember("LEAD", ZHANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM));

        SqlauditException self = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), CREATOR, true, "自审"));
        assertEquals("SELF_APPROVAL_FORBIDDEN", self.getCode());

        ApprovalDecision ok = engine.submitDecision(ticket.getId(), ZHANG, true, "同意");
        assertTrue(ok.ticketApproved());
    }

    // ---------------- 非当前节点 / 非审批人 ----------------

    @Test
    void 非当前节点决策_被拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(2, 12, LI)), false, RiskLevel.MEDIUM));

        // 节点 1 未过，李四（节点 2）不能抢跑
        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), LI, true, "抢先"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", ex.getCode());
    }

    @Test
    void 非节点审批人决策_被拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), WANG, true, "路人"));
        assertEquals("APPROVAL_TARGET_NOT_FOUND", ex.getCode());
    }

    // ---------------- 幂等重放 ----------------

    @Test
    void 重复决策_同结果幂等_改结果抛异常() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(userAnd(1, 11, ZHANG), userAnd(1, 12, LI)), false, RiskLevel.HIGH));

        engine.submitDecision(ticket.getId(), ZHANG, true, "ok");
        ApprovalDecision again = engine.submitDecision(ticket.getId(), ZHANG, true, "ok again");
        assertFalse(again.nodePassed(), "同结果重复提交：幂等放行，节点仍未过");

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.submitDecision(ticket.getId(), ZHANG, false, "改主意"));
        assertEquals("APPROVAL_ALREADY_DECIDED", ex.getCode());
        assertEquals(TicketStatus.AUDITING, statusOf(ticket));
    }

    // ---------------- 二次确认（README §4.6：接口权限控制，引擎不再判角色） ----------------

    @Test
    void 二次确认_CRITICAL链全过只到approved_需显式确认才可执行() {
        Ticket ticket = newAuditingTicket(RiskLevel.CRITICAL, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG)), true, RiskLevel.CRITICAL));

        assertThrows(IllegalStateTransitionException.class,
                () -> engine.confirmExecution(ticket.getId(), WANG));

        engine.submitDecision(ticket.getId(), ZHANG, true, "同意");

        assertEquals(TicketStatus.APPROVED, statusOf(ticket));
        assertTrue(engine.needsSecondConfirm(ticket.getId()));
        assertFalse(engine.canExecute(ticket.getId()), "二次确认前不得执行");

        SqlauditException self = assertThrows(SqlauditException.class,
                () -> engine.confirmExecution(ticket.getId(), CREATOR));
        assertEquals("SELF_CONFIRM_FORBIDDEN", self.getCode());

        // 无 DBA/ADMIN 角色的普通用户也可以二次确认（角色校验由接口权限点控制，§5）
        engine.confirmExecution(ticket.getId(), WANG);
        assertTrue(engine.canExecute(ticket.getId()));
    }

    @Test
    void 二次确认_无secondConfirm链_直接可执行() {
        Ticket ticket = newAuditingTicket(RiskLevel.HIGH, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.HIGH));
        engine.submitDecision(ticket.getId(), ZHANG, true, "ok");

        assertFalse(engine.needsSecondConfirm(ticket.getId()));
        assertTrue(engine.canExecute(ticket.getId()));
    }

    // ---------------- 审批转交（代理人） ----------------

    @Test
    void 转交_USER记录_转给有效用户并留痕() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        engine.transfer(ticket.getId(), ZHANG, WANG);

        List<Approval> rows = approvalDao.listByTicket(ticket.getId());
        assertEquals(2, rows.size());
        Approval original = rows.stream()
                .filter(r -> r.getResult() == ApprovalResult.TRANSFERRED).findFirst().orElseThrow();
        assertEquals(ZHANG, original.getDecidedBy(), "原记录留痕：decided_by = 转交人");
        Approval delegated = rows.stream()
                .filter(r -> r.getResult() == ApprovalResult.PENDING).findFirst().orElseThrow();
        assertEquals(ApproverType.USER, delegated.getApproverType());
        assertEquals("300", delegated.getApproverRef());

        // 代理人接管后可以决策
        ApprovalDecision ok = engine.submitDecision(ticket.getId(), WANG, true, "接手同意");
        assertTrue(ok.ticketApproved());
    }

    @Test
    void 转交_不能转给提交人() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), ZHANG, CREATOR));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void 转交_不能转给本人或停用用户() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(user(1, 11, ZHANG)), false, RiskLevel.MEDIUM));

        assertEquals("INVALID_REQUEST", assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), ZHANG, ZHANG)).getCode());

        users.setStatus(WANG, UserStatus.DISABLED);
        assertEquals("FORBIDDEN", assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), ZHANG, WANG)).getCode());
    }

    @Test
    void 转交_ROLE记录_双方都须持有角色() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", ZHANG);
        roles.addMember("LEAD", WANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM));

        // 李四不持有 LEAD → 拒绝
        assertEquals("FORBIDDEN", assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), ZHANG, LI)).getCode());

        // 王五持有 LEAD → 可以转
        engine.transfer(ticket.getId(), ZHANG, WANG);
        List<Approval> rows = approvalDao.listByTicket(ticket.getId());
        assertTrue(rows.stream().anyMatch(r -> r.getResult() == ApprovalResult.TRANSFERRED));
        assertTrue(rows.stream().anyMatch(r -> ApprovalResult.PENDING == r.getResult()
                && "300".equals(r.getApproverRef())));
    }

    @Test
    void 转交_ROLE记录_非角色成员不能发起() {
        roles.addRole("LEAD");
        roles.addMember("LEAD", ZHANG);
        roles.addMember("LEAD", WANG);
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket,
                new ApprovalChain(List.of(role(1, 11, "LEAD")), false, RiskLevel.MEDIUM));

        // 李四不持有 LEAD，不能转交这条 ROLE 记录
        assertEquals("NO_PENDING_APPROVAL", assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), LI, WANG)).getCode());
    }

    @Test
    void 转交_代理人已在本组_拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(1, 12, WANG)), false, RiskLevel.MEDIUM));

        SqlauditException ex = assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), ZHANG, WANG));
        assertEquals("FORBIDDEN", ex.getCode(), "代理人已在本组 → 拒绝");
    }

    @Test
    void 转交_非当前节点或非本人记录_拒绝() {
        Ticket ticket = newAuditingTicket(RiskLevel.MEDIUM, 1L, CREATOR);
        engine.createNodes(ticket, new ApprovalChain(
                List.of(user(1, 11, ZHANG), user(2, 12, LI)), false, RiskLevel.MEDIUM));

        // 李四是节点 2，当前组是节点 1 → 不能转交
        assertEquals("NO_PENDING_APPROVAL", assertThrows(SqlauditException.class,
                () -> engine.transfer(ticket.getId(), LI, WANG)).getCode());
    }
}
