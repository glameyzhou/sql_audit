package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.ApprovalRouter;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 工单服务（设计文档 §3.1 生命周期 / §3.2 状态机 / §4.4 影响面 / §4.5 审批路由）。
 *
 * <p>关键约束（B-3 验收硬性要求）：</p>
 * <ul>
 *   <li>{@link #submitForAudit}：存在 ERROR 级规则命中（如 R_NO_WHERE）的工单
 *       <strong>拒绝提交</strong>，停留 draft；</li>
 *   <li>{@link #submitForApproval}：影响面评估未完成（§4.4 异步任务未返回）
 *       <strong>不允许提交审批</strong>；提交时按 {@link ApprovalRouter} 取最严链
 *       （多实例逐实例路由后取最严），生成审批节点；LOW 空链自动通过留痕；</li>
 *   <li>{@link #cancel}：任意非终态可取消，终态走状态机必然抛非法迁移异常；
 *   <li>{@link #checkExpiry}：approved 超过 approval_ttl 未执行 → expired
 *       （§3.5 审批时效，expired 需重新审批，走新工单）。</li>
 * </ul>
 */
public class TicketService {

    private final TicketDao ticketDao;
    private final TicketUnitWriter unitWriter;
    private final TicketStateMachine stateMachine;
    private final ApprovalFlowEngine approvalEngine;
    private final ApprovalRouter approvalRouter;

    public TicketService(TicketDao ticketDao, TicketUnitWriter unitWriter,
                         TicketStateMachine stateMachine, ApprovalFlowEngine approvalEngine,
                         ApprovalRouter approvalRouter) {
        this.ticketDao = Objects.requireNonNull(ticketDao, "ticketDao");
        this.unitWriter = Objects.requireNonNull(unitWriter, "unitWriter");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.approvalEngine = Objects.requireNonNull(approvalEngine, "approvalEngine");
        this.approvalRouter = Objects.requireNonNull(approvalRouter, "approvalRouter");
    }

    /**
     * 创建 DRAFT 工单（含 units 落库）。
     *
     * <p>风险等级由提交前的预览链路（解析 → 规则检查 → RiskAssessor，§3.1 时序）给出；
     * rollbackSummary 为提交预览时的「可回滚 / 不可回滚」清单（§5.1）。</p>
     *
     * @param environment 目标环境（prod / test / dev，见 {@link Environment}）
     * @param units       语句列表（一条语句 = 一个 unit），非空
     * @throws SqlauditException code=EMPTY_UNITS / EMPTY_INSTANCES
     */
    public Ticket createTicket(String title, long creatorId, List<Long> instanceIds, Environment environment,
                               RiskLevel riskLevel, String rollbackSummary, String sqlText, String shardConfig,
                               List<TicketUnit> units) {
        if (units == null || units.isEmpty()) {
            throw new SqlauditException(ErrorCode.EMPTY_UNITS, "工单至少包含一条语句（§3.1：一条语句 = 一个 unit）");
        }
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new SqlauditException(ErrorCode.EMPTY_INSTANCES, "工单必须指定目标实例");
        }

        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceIds(new ArrayList<>(instanceIds));
        ticket.setEnvironment(environment);
        ticket.setRiskLevel(riskLevel);
        ticket.setStatus(TicketStatus.DRAFT);
        ticket.setRollbackSummary(rollbackSummary);
        ticket.setSqlText(sqlText);
        ticket.setShardConfig(shardConfig);
        ticket.setTraceId(UUID.randomUUID().toString());
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        long ticketId = ticketDao.insertTicket(ticket);

        for (int i = 0; i < units.size(); i++) {
            TicketUnit unit = units.get(i);
            unit.setTicketId(ticketId);
            unit.setUnitOrder(i + 1);
            unit.setStatus(UnitStatus.PENDING);
            unit.setIntentWritten(false);
            if (unit.getCreatedAt() == null) {
                unit.setCreatedAt(now);
            }
            unitWriter.insert(unit);
        }
        return requireTicket(ticketId);
    }

    /**
     * 提交审核：draft → submitted → auditing。
     *
     * @param outcome 规则引擎同步首轮结果
     * @throws SqlauditException code=ERROR_RULE_BLOCKED（ERROR 级命中阻断提交，工单停留 draft）
     */
    public void submitForAudit(long ticketId, AuditOutcome outcome) {
        Ticket ticket = requireTicket(ticketId);
        Objects.requireNonNull(outcome, "outcome");

        // §4.3：ERROR 阻断提交（如 R_NO_WHERE），工单停留 draft，不允许进入审核
        if (outcome.errorBlocked()) {
            throw new SqlauditException(ErrorCode.ERROR_RULE_BLOCKED,
                    "存在 ERROR 级规则命中（如 R_NO_WHERE），禁止提交，ticket=" + ticketId);
        }

        String actor = String.valueOf(ticket.getCreatorId());
        stateMachine.transition(ticket, TicketStatus.SUBMITTED, actor);
        stateMachine.transition(ticket, TicketStatus.AUDITING, actor);
    }

    /**
     * 提交审批：auditing 态下按最严链生成审批节点（§4.5）。
     *
     * <p>路由说明：对工单的每个实例逐一路由后取最严链（取最严语义延伸到实例维度）；
     * B-3 不传规则命中（errorBlocked 的工单到不了这一步，nonOverridable 的
     * CRITICAL 锁定由 router 的 riskLevel 分支覆盖——CRITICAL 恒带二次确认）。
     * hits 级路由在 W2 装配层接入 audit_result 后补传。</p>
     *
     * @param impactAssessmentDone 影响面评估（§4.4 异步任务）是否完成
     * @throws SqlauditException               code=IMPACT_ASSESSMENT_INCOMPLETE /
     *                                          APPROVAL_NODES_EXIST（重复提交）
     * @throws IllegalStateTransitionException 工单不在 auditing 态
     */
    public void submitForApproval(long ticketId, boolean impactAssessmentDone) {
        Ticket ticket = requireTicket(ticketId);

        // §4.4：影响面评估未完成的工单不允许提交审批（验收硬性要求）
        if (!impactAssessmentDone) {
            throw new SqlauditException(ErrorCode.IMPACT_ASSESSMENT_INCOMPLETE,
                    "影响面评估未完成，不允许提交审批（§4.4），ticket=" + ticketId);
        }

        TicketStatus status = ticket.getStatus();
        if (status != TicketStatus.AUDITING) {
            throw new IllegalStateTransitionException(ticket.getStatus().getCode(),
                    TicketStatus.APPROVED.getCode(), "仅 auditing 态可提交审批");
        }

        ApprovalChain chain = routeStrictestChain(ticket);
        approvalEngine.createNodes(ticket, chain);

        // LOW 空链（§4.5：自动通过，留痕）——无审批节点，工单直接转 approved
        if (chain.nodes().isEmpty()) {
            stateMachine.transition(ticket, TicketStatus.APPROVED, "SYSTEM");
        }
    }

    /**
     * 取消工单：任意非终态 → cancelled（§3.2），终态由状态机抛非法迁移异常。
     */
    public void cancel(long ticketId, String actor) {
        Ticket ticket = requireTicket(ticketId);
        stateMachine.transition(ticket, TicketStatus.CANCELLED, actor);
    }

    /**
     * 审批时效检查（§3.5）：approved 超过 ttl 未执行 → expired（需重新审批，走新工单）。
     *
     * <p>时效基准为进入 approved 的时刻（updateStatus 刷新的 updated_at）。</p>
     *
     * @return true 表示本次调用把工单置为 expired；false 表示未超时或不在 approved 态
     */
    public boolean checkExpiry(long ticketId, Instant now, Duration ttl) {
        Ticket ticket = requireTicket(ticketId);
        if (ticket.getStatus() != TicketStatus.APPROVED) {
            return false;
        }
        LocalDateTime approvedAt = ticket.getUpdatedAt() != null ? ticket.getUpdatedAt() : ticket.getCreatedAt();
        if (approvedAt == null) {
            return false;
        }
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        if (approvedAt.plus(ttl).isBefore(nowLocal)) {
            stateMachine.transition(ticket, TicketStatus.EXPIRED, "approval-ttl-checker");
            return true;
        }
        return false;
    }

    /** 查询工单（快照）；不存在抛 TICKET_NOT_FOUND。 */
    public Ticket getTicket(long ticketId) {
        return requireTicket(ticketId);
    }

    /** 多实例逐一路由后取最严链（§4.5 取最严，不取命中第一条）。 */
    private ApprovalChain routeStrictestChain(Ticket ticket) {
        RiskLevel riskLevel = ticket.getRiskLevel();
        java.util.Map<String, Integer> rank = approvalRouter.roleRanks();
        ApprovalChain strictest = null;
        for (Long instanceId : ticket.getInstanceIds()) {
            ApprovalChain chain = approvalRouter.route(riskLevel, instanceId, null);
            if (strictest == null
                    || chain.strictness(rank) > strictest.strictness(rank)
                    || chain.strictness(rank) == strictest.strictness(rank) && chain.secondConfirm()) {
                strictest = chain;
            }
        }
        return strictest;
    }

    private Ticket requireTicket(long ticketId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_FOUND, "工单不存在：" + ticketId);
        }
        return ticket;
    }
}
