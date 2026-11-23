package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalResult;
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
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 工单服务（README §4.2 生命周期与状态机、README §4.3 影响面与审批路由）。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>{@link #createTicket}：单实例工单（决定 10）——一个 {@code instanceId}，
 *       不再有多实例取最严逻辑；</li>
 *   <li>{@link #submitForAudit}：存在 ERROR 级规则命中（如 R_NO_WHERE）的工单
 *       <strong>拒绝提交</strong>，停留 draft；</li>
 *   <li>{@link #submitForApproval}：影响面评估已同步并入提交链路，建单即生成审批节点；
 *       按 {@link ApprovalRouter}（追加模式）路由该实例的生效链并生成审批节点；
 *       <strong>非 LOW 空链 / 无可审人由引擎拒绝</strong>（不再自动批准）；
 *       LOW 空链或全部节点被跳过（提交人防自审）→ 直接 approved 留痕；</li>
 *   <li>{@link #cancel}：任意非终态可取消，终态走状态机必然抛非法迁移异常；</li>
 *   <li>{@link #checkExpiry}：approved 超过 approval_ttl 未执行 → expired
 *       （README §4.2 审批时效，expired 需重新审批，走新工单）。</li>
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
     * 创建 DRAFT 工单（含 units 落库，单实例）。
     *
     * <p>风险等级由提交前的预览链路（解析 → 规则检查 → RiskAssessor，README §4.2 时序）给出；
     * rollbackSummary 为提交预览时的「可回滚 / 不可回滚」清单（README §4.4）。</p>
     *
     * @param instanceId 目标实例（单实例，决定 10）
     * @param units      语句列表（一条语句 = 一个 unit），非空
     * @throws SqlauditException code=EMPTY_UNITS / EMPTY_INSTANCES
     */
    public Ticket createTicket(String title, long creatorId, long instanceId,
                               RiskLevel riskLevel, String rollbackSummary, String sqlText,
                               String shardConfig, List<TicketUnit> units) {
        if (units == null || units.isEmpty()) {
            throw new SqlauditException(ErrorCode.EMPTY_UNITS, "工单至少包含一条语句（一条语句 = 一个 unit）");
        }
        if (instanceId <= 0) {
            throw new SqlauditException(ErrorCode.EMPTY_INSTANCES, "工单必须指定目标实例");
        }

        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceId(instanceId);
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

        // README §4.3：ERROR 阻断提交（如 R_NO_WHERE），工单停留 draft，不允许进入审核
        if (outcome.errorBlocked()) {
            throw new SqlauditException(ErrorCode.ERROR_RULE_BLOCKED,
                    "存在 ERROR 级规则命中（如 R_NO_WHERE），禁止提交，ticket=" + ticketId);
        }

        String actor = String.valueOf(ticket.getCreatorId());
        stateMachine.transition(ticket, TicketStatus.SUBMITTED, actor);
        stateMachine.transition(ticket, TicketStatus.AUDITING, actor);
    }

    /**
     * 生成审批节点：auditing 态下按该实例的生效链生成审批节点（追加模式，决定 9）。
     *
     * <p>提交链路已合并：建单后立即调用本方法（影响面评估同步完成，无独立「提交审批」步骤）。
     * 非 LOW 且生效链为空 → {@code APPROVAL_FLOW_NOT_CONFIGURED}；
     * 审批人全部是提交人（防自审跳过）→ {@code NO_ELIGIBLE_APPROVER}；
     * LOW 空链或全部节点被跳过 → 直接 approved（留痕，SYSTEM）。</p>
     *
     * @throws SqlauditException               code=APPROVAL_NODES_EXIST（重复提交）/
     *                                          APPROVAL_FLOW_NOT_CONFIGURED /
     *                                          NO_ELIGIBLE_APPROVER
     * @throws IllegalStateTransitionException 工单不在 auditing 态
     */
    public void submitForApproval(long ticketId) {
        Ticket ticket = requireTicket(ticketId);

        TicketStatus status = ticket.getStatus();
        if (status != TicketStatus.AUDITING) {
            throw new IllegalStateTransitionException(ticket.getStatus().getCode(),
                    TicketStatus.APPROVED.getCode(), "仅 auditing 态可生成审批节点");
        }

        // 单实例路由（决定 9 追加模式；旧的「多实例取最严」已删除）
        ApprovalChain chain = approvalRouter.route(ticket.getRiskLevel(), ticket.getInstanceId());
        List<Approval> nodes = approvalEngine.createNodes(ticket, chain);

        // LOW 空链（免审）/ 全部节点被防自审跳过 → 直接 approved（留痕）
        if (nodes.isEmpty() || nodes.stream().allMatch(r -> r.getResult() == ApprovalResult.SKIPPED)) {
            stateMachine.transition(ticket, TicketStatus.APPROVED, "SYSTEM");
        }
    }

    /**
     * 取消工单：任意非终态 → cancelled（README §4.2），终态由状态机抛非法迁移异常。
     */
    public void cancel(long ticketId, String actor) {
        Ticket ticket = requireTicket(ticketId);
        stateMachine.transition(ticket, TicketStatus.CANCELLED, actor);
    }

    /**
     * 审批时效检查（README §4.2）：approved 超过 ttl 未执行 → expired（需重新审批，走新工单）。
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

    private Ticket requireTicket(long ticketId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_FOUND, "工单不存在：" + ticketId);
        }
        return ticket;
    }
}
