package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批流引擎（设计文档 §3.4 / §4.5 / §8.2）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>{@link #createNodes}：按 {@link ApprovalChain} 生成 approval 记录（nodeOrder 1..n，
 *       mode 从链语义映射：单节点链 = OR 或签，多节点链 = AND 会签）。B-3 阶段节点为
 *       ROLE 占位（approver_id=0 表示「按角色解析」），W2 装配时补角色 → 用户解析，
 *       解析后同一节点按人展开多行即为真实会签组；</li>
 *   <li>{@link #submitDecision}：审批决策。<strong>防自审硬校验</strong>（§3.4：
 *       approver_id != ticket.creator_id，不是前端隐藏按钮）；会签全部通过才过节点、
 *       一人驳回即驳节点；或签一人通过即过节点；全部节点通过 → 工单转 approved
 *       （审批 ≠ 执行，§3.4）；</li>
 *   <li>{@link #confirmExecution}：CRITICAL 链（secondConfirm）执行前的显式二次确认，
 *       由 DBA / 管理员完成（角色校验留给 W2）；</li>
 *   <li>{@link #listPendingOverdue}：超时待审批查询（企微提醒 W2 接）。</li>
 * </ul>
 *
 * <p>审批链（含 secondConfirm 语义）与二次确认记录当前存内存（fake 阶段），
 * W2 落库后由 flow_node / 审计持久化替代。</p>
 */
public class ApprovalFlowEngine {

    private final ApprovalDao approvalDao;
    private final TicketDao ticketDao;
    private final TicketStateMachine stateMachine;

    /** ticketId → 审批链（secondConfirm 语义来源；W2 落库后移除） */
    private final Map<Long, ApprovalChain> chains = new ConcurrentHashMap<>();
    /** ticketId → 二次确认人（执行前显式确认，§3.4；W2 落审计后移除） */
    private final Map<Long, Long> executionConfirmers = new ConcurrentHashMap<>();

    public ApprovalFlowEngine(ApprovalDao approvalDao, TicketDao ticketDao,
                              TicketStateMachine stateMachine) {
        this.approvalDao = Objects.requireNonNull(approvalDao, "approvalDao");
        this.ticketDao = Objects.requireNonNull(ticketDao, "ticketDao");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    /**
     * 审批链 → 节点模型（nodeOrder 1..n）。
     *
     * <p>mode 映射（任务约定）：单节点链 = OR（或签），多节点链 = AND（会签）。</p>
     */
    public List<ApprovalNode> buildNodes(ApprovalChain chain) {
        Objects.requireNonNull(chain, "chain");
        List<String> roles = chain.nodes();
        ApprovalMode mode = roles.size() == 1 ? ApprovalMode.OR : ApprovalMode.AND;
        List<ApprovalNode> nodes = new ArrayList<>(roles.size());
        for (int i = 0; i < roles.size(); i++) {
            nodes.add(new ApprovalNode(i + 1, ApprovalNode.TYPE_ROLE, roles.get(i), mode));
        }
        return nodes;
    }

    /**
     * 按链节点顺序生成 approval 记录（PENDING）。
     *
     * <p>ROLE 占位：approver_id = 0 表示「按角色解析」，node_id = 0 占位（B-3 无
     * flow_node 记录），W2 装配时补角色 → 用户解析与 flow_node 关联。同一工单
     * 重复生成抛异常（防止 submitForApproval 重复调用导致节点翻倍）。</p>
     */
    public List<Approval> createNodes(Ticket ticket, ApprovalChain chain) {
        Objects.requireNonNull(ticket, "ticket");
        if (!approvalDao.listByTicket(ticket.getId()).isEmpty()) {
            throw new SqlauditException("APPROVAL_NODES_EXIST",
                    "工单 " + ticket.getId() + " 已生成审批节点，不得重复生成");
        }
        LocalDateTime now = LocalDateTime.now();
        for (ApprovalNode node : buildNodes(chain)) {
            Approval approval = new Approval();
            approval.setTicketId(ticket.getId());
            approval.setNodeId(0L);
            approval.setNodeOrder(node.nodeOrder());
            approval.setApproverId(0L);
            approval.setMode(node.mode().name());
            approval.setResult(ApprovalResult.PENDING.name());
            approval.setCreatedAt(now);
            approvalDao.insert(approval);
        }
        chains.put(ticket.getId(), chain);
        return approvalDao.listByTicket(ticket.getId());
    }

    /**
     * 提交审批决策。
     *
     * <p>决策只能作用于<strong>当前节点</strong>（nodeOrder 最小的含 PENDING 记录的节点，
     * 节点串行推进）。目标记录选取：当前节点下该审批人的 PENDING 记录；若无则认领
     * ROLE 占位记录（approver_id=0）。同结果重复提交幂等（按工单当前状态重建返回值，
     * 即使工单已推进到 approved / rejected 也放行）；不同结果重复提交抛异常。</p>
     *
     * @throws SqlauditException code=SELF_APPROVAL_FORBIDDEN（防自审硬校验，§3.4）/
     *                           TICKET_NOT_AUDITING / NO_PENDING_APPROVAL /
     *                           APPROVAL_TARGET_NOT_FOUND / APPROVAL_ALREADY_DECIDED
     */
    public ApprovalDecision submitDecision(long ticketId, long approverId, boolean approve, String comment) {
        Ticket ticket = requireTicket(ticketId);

        // §3.4 防自审硬校验：提交人不得审批自己的工单（不是前端隐藏按钮）
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == approverId) {
            throw new SqlauditException("SELF_APPROVAL_FORBIDDEN",
                    "提交人不得审批自己的工单（§3.4 防自审，ticket=" + ticketId + "）");
        }

        ApprovalResult decision = approve ? ApprovalResult.APPROVED : ApprovalResult.REJECTED;
        List<Approval> rows = approvalDao.listByTicket(ticketId);

        // 幂等 / 冲突：该审批人已有既成决策（重放场景工单可能已推进到 approved/rejected）
        for (Approval row : rows) {
            if (row.getApproverId() != null && row.getApproverId() == approverId
                    && !ApprovalResult.PENDING.name().equals(row.getResult())) {
                if (decision.name().equals(row.getResult())) {
                    return rebuildDecision(ticket, row.getNodeOrder(), approverId, decision);
                }
                throw new SqlauditException("APPROVAL_ALREADY_DECIDED",
                        "审批人 " + approverId + " 对工单 " + ticketId + " 已做出不同决策");
            }
        }

        TicketStatus status = TicketStateMachine.parse(ticket.getStatus());
        if (status != TicketStatus.AUDITING) {
            throw new SqlauditException("TICKET_NOT_AUDITING",
                    "工单 " + ticketId + " 状态为 " + ticket.getStatus() + "，不在审批中");
        }

        int currentNode = rows.stream()
                .filter(r -> ApprovalResult.PENDING.name().equals(r.getResult()))
                .mapToInt(Approval::getNodeOrder)
                .min()
                .orElseThrow(() -> new SqlauditException("NO_PENDING_APPROVAL",
                        "工单 " + ticketId + " 没有待审批节点"));

        // 目标记录：该审批人的 PENDING 记录，否则认领 ROLE 占位记录（approver_id=0）
        Approval target = rows.stream()
                .filter(r -> r.getNodeOrder() == currentNode
                        && ApprovalResult.PENDING.name().equals(r.getResult())
                        && (r.getApproverId() != null && r.getApproverId() == approverId
                            || r.getApproverId() != null && r.getApproverId() == 0L))
                .findFirst()
                .orElseThrow(() -> new SqlauditException("APPROVAL_TARGET_NOT_FOUND",
                        "节点 " + currentNode + " 无审批人 " + approverId + " 的待审批记录（非当前节点或无权限）"));

        int updated = approvalDao.updateResult(target.getId(), approverId,
                ApprovalResult.PENDING.name(), decision.name(), comment, LocalDateTime.now());
        if (updated == 0) {
            // 快照后又被并发决策：极端竞争，直接报冲突（同结果重放走上面的幂等分支）
            throw new SqlauditException("APPROVAL_ALREADY_DECIDED",
                    "审批记录已被决策（并发冲突），ticket=" + ticketId + " node=" + currentNode);
        }

        // 重新加载，按节点组判定
        List<Approval> latest = approvalDao.listByTicket(ticketId);
        List<Approval> nodeRows = latest.stream()
                .filter(r -> r.getNodeOrder() == currentNode)
                .toList();
        ApprovalMode mode = ApprovalMode.valueOf(nodeRows.get(0).getMode());
        boolean anyApproved = nodeRows.stream()
                .anyMatch(r -> ApprovalResult.APPROVED.name().equals(r.getResult()));
        boolean anyRejected = nodeRows.stream()
                .anyMatch(r -> ApprovalResult.REJECTED.name().equals(r.getResult()));

        boolean nodePassed;
        boolean nodeRejected;
        if (mode == ApprovalMode.AND) {
            // 会签：任一 REJECTED 即驳回；全部 APPROVED 才通过
            nodeRejected = anyRejected;
            nodePassed = !anyRejected && nodeRows.stream()
                    .allMatch(r -> ApprovalResult.APPROVED.name().equals(r.getResult()));
        } else {
            // 或签：任一 APPROVED 即通过；无人通过且有人驳回即驳回（先决者胜）
            nodePassed = anyApproved;
            nodeRejected = !anyApproved && anyRejected;
        }

        String actor = String.valueOf(approverId);
        if (nodeRejected) {
            stateMachine.transition(ticket, TicketStatus.REJECTED, actor);
            return new ApprovalDecision(ticketId, currentNode, approverId, decision, false, false, true);
        }
        if (!nodePassed) {
            // 会签：还有审批人未表态，节点与工单均保持原状
            return new ApprovalDecision(ticketId, currentNode, approverId, decision, false, false, false);
        }

        // 节点通过：还有后续节点则等待（节点串行）
        boolean hasNextNode = latest.stream()
                .anyMatch(r -> r.getNodeOrder() > currentNode
                        && ApprovalResult.PENDING.name().equals(r.getResult()));
        if (hasNextNode) {
            return new ApprovalDecision(ticketId, currentNode, approverId, decision, true, false, false);
        }

        // 全部节点通过 → 工单可转 APPROVED（审批 ≠ 执行，§3.4）
        stateMachine.transition(ticket, TicketStatus.APPROVED, actor);
        return new ApprovalDecision(ticketId, currentNode, approverId, decision, true, true, false);
    }

    /**
     * 执行前二次确认（§3.4：CRITICAL 链 secondConfirm，由 DBA / 管理员完成）。
     *
     * <p>审批 ≠ 执行：全部节点通过只到 approved；带 secondConfirm 的链必须先经本方法
     * 显式确认才允许执行（{@link #canExecute}）。角色校验（DBA/ADMIN）留给 W2，
     * B-3 仅硬校验提交人不得确认自己的工单。</p>
     *
     * @throws SqlauditException               code=SELF_CONFIRM_FORBIDDEN / TICKET_NOT_FOUND
     * @throws IllegalStateTransitionException 工单不在 approved 态
     */
    public void confirmExecution(long ticketId, long confirmerId) {
        Ticket ticket = requireTicket(ticketId);
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == confirmerId) {
            throw new SqlauditException("SELF_CONFIRM_FORBIDDEN",
                    "提交人不得二次确认自己的工单（§3.4，ticket=" + ticketId + "）");
        }
        TicketStatus status = TicketStateMachine.parse(ticket.getStatus());
        if (status != TicketStatus.APPROVED) {
            throw new IllegalStateTransitionException(ticket.getStatus(),
                    TicketStateMachine.dbValue(TicketStatus.EXECUTING),
                    "二次确认仅对 approved 工单有效");
        }
        executionConfirmers.put(ticketId, confirmerId);
    }

    /** 该工单的审批链是否要求执行前二次确认。 */
    public boolean needsSecondConfirm(long ticketId) {
        ApprovalChain chain = chains.get(ticketId);
        return chain != null && chain.secondConfirm();
    }

    /**
     * 工单当前是否允许进入执行（executor / W2 装配调用）：
     * approved 且（无需二次确认 或 已确认）。
     */
    public boolean canExecute(long ticketId) {
        Ticket ticket = requireTicket(ticketId);
        if (TicketStateMachine.parse(ticket.getStatus()) != TicketStatus.APPROVED) {
            return false;
        }
        return !needsSecondConfirm(ticketId) || executionConfirmers.containsKey(ticketId);
    }

    /**
     * 超时待审批查询（§8.2 超时企微提醒，W2 接推送）。
     *
     * <p>只返回仍在审批中（auditing）工单的<strong>当前节点</strong> PENDING 记录——
     * 节点串行推进，提醒未来节点的审批人只会制造噪音；已驳回工单遗留的
     * PENDING 记录也不再提醒。</p>
     */
    public List<Approval> listPendingOverdue(Instant now, Duration timeout) {
        LocalDateTime threshold = LocalDateTime.ofInstant(now, ZoneId.systemDefault()).minus(timeout);
        List<Approval> candidates = approvalDao.listPendingCreatedBefore(threshold);

        Map<Long, List<Approval>> byTicket = new java.util.LinkedHashMap<>();
        for (Approval candidate : candidates) {
            byTicket.computeIfAbsent(candidate.getTicketId(), k -> new ArrayList<>()).add(candidate);
        }

        List<Approval> result = new ArrayList<>();
        for (Map.Entry<Long, List<Approval>> entry : byTicket.entrySet()) {
            Ticket ticket = ticketDao.findById(entry.getKey());
            if (ticket == null || TicketStateMachine.parse(ticket.getStatus()) != TicketStatus.AUDITING) {
                continue;
            }
            // 当前节点 = 该工单全部 PENDING 记录里 nodeOrder 最小者
            int currentNode = approvalDao.listByTicket(entry.getKey()).stream()
                    .filter(r -> ApprovalResult.PENDING.name().equals(r.getResult()))
                    .mapToInt(Approval::getNodeOrder)
                    .min()
                    .orElse(Integer.MAX_VALUE);
            for (Approval candidate : entry.getValue()) {
                if (candidate.getNodeOrder() == currentNode) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    /** 幂等重放：按工单当前状态重建返回值（不再触发状态迁移与节点判定）。 */
    private ApprovalDecision rebuildDecision(Ticket ticket, int nodeOrder, long approverId,
                                             ApprovalResult result) {
        TicketStatus status = TicketStateMachine.parse(ticket.getStatus());
        boolean approved = status == TicketStatus.APPROVED;
        boolean rejected = status == TicketStatus.REJECTED;
        return new ApprovalDecision(ticket.getId(), nodeOrder, approverId, result,
                approved, approved, rejected);
    }

    private Ticket requireTicket(long ticketId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            throw new SqlauditException("TICKET_NOT_FOUND", "工单不存在：" + ticketId);
        }
        return ticket;
    }
}
