package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.ChainNode;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 审批流引擎（README §4.6 审批流、README §4.3 风险分级、README §4.5 权限与角色，决定 9 追加模式）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>{@link #resolveNodes}：把生效链（{@link ApprovalChain}）解析成审批记录（试解析，
 *       不落库）——USER → ref = 用户 id（必须 ACTIVE）；ROLE → ref = 角色 code（须至少有一个
 *       除提交人以外的 ACTIVE 成员）；INSTANCE_OWNER → 解析实例 {@code owner_id} 为具体用户
 *       （快照，必须 ACTIVE）。解析结果是提交人本人 → 该记录 {@code SKIPPED}
 *       （decided_by = 提交人，留痕）。空链且非 LOW → {@code APPROVAL_FLOW_NOT_CONFIGURED}；
 *       无可审人 → {@code NO_ELIGIBLE_APPROVER}；</li>
 *   <li>{@link #createNodes}：试解析 + 落库（撞唯一键 = 并发重复生成 → 失败），并标记
 *       二次确认（{@code CRITICAL} 恒要求，README §4.6）；</li>
 *   <li>{@link #submitDecision}：审批决策——<strong>防自审硬校验</strong>；只能作用于当前组；
 *       USER / INSTANCE_OWNER 记录要求 ref = 本人；ROLE 记录要求本人持有该角色且不是提交人
 *       （认领时写 decided_by）。组判定：忽略 TRANSFERRED，SKIPPED 视为通过，AND = 其余全部
 *       APPROVED，OR = 任一 APPROVED；驳回语义见方法注释；</li>
 *   <li>{@link #transfer}：转交——ROLE 记录由持有该角色者转交，代理人须持有同一角色；
 *       USER / INSTANCE_OWNER 记录可转给提交人以外的任一 ACTIVE 用户；代理人已在本组拒绝。
 *       原记录置 TRANSFERRED + 新 USER 记录（同一事务，走 {@link ApprovalDao#transfer}）；</li>
 *   <li>{@link #confirmExecution}：CRITICAL 执行前二次确认——<strong>不做角色校验</strong>
 *       （接口权限控制），只校验不是提交人、工单为 APPROVED。</li>
 * </ul>
 */
public class ApprovalFlowEngine {

    private final ApprovalDao approvalDao;
    private final TicketDao ticketDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final InstanceDao instanceDao;
    private final TicketStateMachine stateMachine;

    public ApprovalFlowEngine(ApprovalDao approvalDao, TicketDao ticketDao, UserDao userDao,
                              RoleDao roleDao, InstanceDao instanceDao,
                              TicketStateMachine stateMachine) {
        this.approvalDao = Objects.requireNonNull(approvalDao, "approvalDao");
        this.ticketDao = Objects.requireNonNull(ticketDao, "ticketDao");
        this.userDao = Objects.requireNonNull(userDao, "userDao");
        this.roleDao = Objects.requireNonNull(roleDao, "roleDao");
        this.instanceDao = Objects.requireNonNull(instanceDao, "instanceDao");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    // ------------------------------------------------------------------
    // 节点解析与生成
    // ------------------------------------------------------------------

    /**
     * 试解析生效链（<strong>不落库</strong>）：返回「将要生成的审批记录」。
     *
     * <p>建单前（server 的 POST /api/tickets）先调本方法校验，失败不建单——传入的
     * {@link Ticket} 可以是未持久化的（只要 riskLevel / instanceId / creatorId 已填）。</p>
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>空链：LOW → 免审（返回空）；非 LOW → {@code APPROVAL_FLOW_NOT_CONFIGURED}；</li>
     *   <li>USER：用户必须存在且 ACTIVE；是提交人 → SKIPPED；</li>
     *   <li>ROLE：角色必须存在，且至少有一个除提交人以外的 ACTIVE 成员；
     *       记录保持 ROLE 类型（提交时由任一持有者认领）；</li>
     *   <li>INSTANCE_OWNER：实例必须存在，owner 必须 ACTIVE；owner 是提交人 → SKIPPED
     *       （记录类型保持 INSTANCE_OWNER，ref = 解析出的负责人用户 id 快照）；</li>
     *   <li>全部记录都是 SKIPPED 且风险非 LOW → {@code NO_ELIGIBLE_APPROVER}。</li>
     * </ul>
     *
     * @throws SqlauditException code=APPROVAL_FLOW_NOT_CONFIGURED / NO_ELIGIBLE_APPROVER
     */
    public List<Approval> resolveNodes(Ticket ticket, ApprovalChain chain) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(chain, "chain");
        RiskLevel riskLevel = Objects.requireNonNull(ticket.getRiskLevel(), "ticket.riskLevel");

        if (chain.nodes().isEmpty()) {
            if (riskLevel != RiskLevel.LOW) {
                throw new SqlauditException(ErrorCode.APPROVAL_FLOW_NOT_CONFIGURED,
                        "风险等级 " + riskLevel.getCode() + " 的生效审批链为空"
                                + "（实例 " + ticket.getInstanceId() + " 无专用流程且无全局流程），拒绝提交");
            }
            return List.of(); // LOW 空链免审（自动通过留痕）
        }

        long submitterId = requireCreatorId(ticket);
        LocalDateTime now = LocalDateTime.now();
        List<Approval> rows = new ArrayList<>(chain.nodes().size());
        for (ChainNode node : chain.nodes()) {
            rows.add(resolveNode(ticket, node, submitterId, now));
        }

        boolean allSkipped = rows.stream().allMatch(r -> r.getResult() == ApprovalResult.SKIPPED);
        if (allSkipped && riskLevel != RiskLevel.LOW) {
            throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                    "生效审批链的审批人全部是提交人本人（防自审跳过），"
                            + "且风险等级 " + riskLevel.getCode() + " 不允许免审，拒绝提交");
        }
        return rows;
    }

    /**
     * 按生效链生成审批记录并落库（{@code PENDING} / {@code SKIPPED}），并标记二次确认。
     *
     * <p>防重复：工单已有审批记录 → {@code APPROVAL_NODES_EXIST}；并发插入撞唯一键
     * （DAO 返回 0）同样视为「已被并发生成」抛 {@code APPROVAL_NODES_EXIST}
     * （JDBC 路径由外层事务整体回滚）。</p>
     *
     * @return 落库后的全部审批记录（含 SKIPPED 留痕）
     */
    public List<Approval> createNodes(Ticket ticket, ApprovalChain chain) {
        Objects.requireNonNull(ticket, "ticket");
        if (!approvalDao.listByTicket(ticket.getId()).isEmpty()) {
            throw new SqlauditException(ErrorCode.APPROVAL_NODES_EXIST,
                    "工单 " + ticket.getId() + " 已生成审批节点，不得重复生成");
        }
        List<Approval> rows = resolveNodes(ticket, chain);
        for (Approval row : rows) {
            long id = approvalDao.insert(row);
            if (id == 0) {
                // 并发：同一工单同一组同一指派对象已被另一个请求生成（唯一键冲突）
                throw new SqlauditException(ErrorCode.APPROVAL_NODES_EXIST,
                        "工单 " + ticket.getId() + " 的审批记录已被并发生成，拒绝重复提交");
            }
        }
        ticketDao.markNeedsSecondConfirm(ticket.getId(), chain.secondConfirm());
        return approvalDao.listByTicket(ticket.getId());
    }

    /** 单节点 → 单条审批记录（USER / ROLE / INSTANCE_OWNER 解析，防自审跳过）。 */
    private Approval resolveNode(Ticket ticket, ChainNode node, long submitterId, LocalDateTime now) {
        Approval approval = new Approval();
        approval.setTicketId(ticket.getId());
        approval.setFlowNodeId(node.flowNodeId());
        approval.setNodeOrder(node.nodeOrder());
        approval.setMode(node.mode());
        approval.setCreatedAt(now);

        switch (Objects.requireNonNull(node.type(), "node.type")) {
            case USER -> {
                long userId = parseUserId(node.ref(), node);
                requireActiveUser(userId, "USER 节点审批人 " + node.ref());
                if (userId == submitterId) {
                    return skipped(approval, node.type(), submitterId, now);
                }
                approval.setApproverType(ApproverType.USER);
                approval.setApproverRef(String.valueOf(userId));
            }
            case ROLE -> {
                String roleCode = node.ref();
                Role role = roleDao.findByCode(roleCode);
                if (role == null) {
                    throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                            "审批链节点引用的角色不存在: " + roleCode);
                }
                if (!roleDao.hasActiveMember(role.getId(), submitterId)) {
                    throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                            "角色 " + roleCode + " 没有除提交人以外的有效成员，无法审批");
                }
                approval.setApproverType(ApproverType.ROLE);
                approval.setApproverRef(role.getCode());
            }
            case INSTANCE_OWNER -> {
                var instance = instanceDao.findById(ticket.getInstanceId());
                if (instance == null) {
                    throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                            "实例 " + ticket.getInstanceId() + " 不存在，无法解析实例负责人");
                }
                Long ownerId = instance.getOwnerId();
                if (ownerId == null) {
                    throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                            "实例 " + ticket.getInstanceId() + " 未配置负责人，无法审批");
                }
                requireActiveUser(ownerId, "实例负责人 " + ownerId);
                if (ownerId == submitterId) {
                    return skipped(approval, node.type(), submitterId, now);
                }
                approval.setApproverType(ApproverType.INSTANCE_OWNER);
                approval.setApproverRef(String.valueOf(ownerId));
            }
            default -> throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                    "未知的审批节点类型: " + node.type());
        }
        approval.setResult(ApprovalResult.PENDING);
        return approval;
    }

    /** 防自审跳过：解析出的审批人就是提交人 → SKIPPED（decided_by = 提交人，留痕）。 */
    private static Approval skipped(Approval approval, ApproverType originalType, long submitterId,
                                    LocalDateTime now) {
        approval.setApproverType(originalType);
        approval.setApproverRef(String.valueOf(submitterId));
        approval.setResult(ApprovalResult.SKIPPED);
        approval.setDecidedBy(submitterId);
        approval.setDecidedAt(now);
        return approval;
    }

    private static long parseUserId(String ref, ChainNode node) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException e) {
            throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                    "USER 节点引用的用户 id 非法: " + ref + "（节点 flow_node_id="
                            + node.flowNodeId() + "）");
        }
    }

    private User requireActiveUser(long userId, String desc) {
        User user = userDao.findById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new SqlauditException(ErrorCode.NO_ELIGIBLE_APPROVER,
                    desc + " 不存在或已停用，无法审批");
        }
        return user;
    }

    private static long requireCreatorId(Ticket ticket) {
        if (ticket.getCreatorId() == null) {
            throw new SqlauditException(ErrorCode.BUSINESS_ERROR, "工单缺少创建人，无法解析审批链");
        }
        return ticket.getCreatorId();
    }

    // ------------------------------------------------------------------
    // 审批决策
    // ------------------------------------------------------------------

    /**
     * 提交审批决策。
     *
     * <p>决策只能作用于<strong>当前组</strong>（含 PENDING 记录的最小 nodeOrder）。
     * USER / INSTANCE_OWNER 记录要求 ref = 本人；ROLE 记录要求本人持有该角色且不是提交人
     * （认领时写 decided_by）。同结果重复提交幂等；不同结果重复提交抛异常。</p>
     *
     * <p>组判定（忽略 TRANSFERRED；SKIPPED 视为通过）：</p>
     * <ul>
     *   <li>AND：任一 REJECTED 即驳回；其余全部 APPROVED（无 PENDING）才通过；</li>
     *   <li>OR：任一 APPROVED 即通过；无人通过且有人驳回即驳回；全组只剩 SKIPPED 视为通过
     *       （该组无人可审，接续下一组）。</li>
     * </ul>
     *
     * @throws SqlauditException code=SELF_APPROVAL_FORBIDDEN / TICKET_NOT_AUDITING /
     *                           NO_PENDING_APPROVAL / APPROVAL_TARGET_NOT_FOUND /
     *                           APPROVAL_ALREADY_DECIDED
     */
    public ApprovalDecision submitDecision(long ticketId, long approverId,
                                           boolean approve, String comment) {
        Ticket ticket = requireTicket(ticketId);

        // README §4.6 防自审硬校验：提交人不得审批自己的工单（不是前端隐藏按钮）
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == approverId) {
            throw new SqlauditException(ErrorCode.SELF_APPROVAL_FORBIDDEN,
                    "提交人不得审批自己的工单（防自审，ticket=" + ticketId + "）");
        }

        ApprovalResult decision = approve ? ApprovalResult.APPROVED : ApprovalResult.REJECTED;
        List<Approval> rows = approvalDao.listByTicket(ticketId);

        // 幂等 / 冲突：该审批人已有既成决策（重放场景工单可能已推进到 approved/rejected）
        for (Approval row : rows) {
            if (row.getDecidedBy() != null && row.getDecidedBy() == approverId
                    && (row.getResult() == ApprovalResult.APPROVED
                        || row.getResult() == ApprovalResult.REJECTED)) {
                if (decision == row.getResult()) {
                    return rebuildDecision(ticket, row.getNodeOrder(), approverId, decision);
                }
                throw new SqlauditException(ErrorCode.APPROVAL_ALREADY_DECIDED,
                        "审批人 " + approverId + " 对工单 " + ticketId + " 已做出不同决策");
            }
        }

        TicketStatus status = ticket.getStatus();
        if (status != TicketStatus.AUDITING) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_AUDITING,
                    "工单 " + ticketId + " 状态为 " + status.getCode() + "，不在审批中");
        }

        int currentNode = currentGroup(rows);

        List<String> myRoles = roleCodesOf(approverId);
        Approval target = rows.stream()
                .filter(r -> r.getNodeOrder() == currentNode
                        && r.getResult() == ApprovalResult.PENDING
                        && canDecide(r, approverId, myRoles))
                .findFirst()
                .orElseThrow(() -> new SqlauditException(ErrorCode.APPROVAL_TARGET_NOT_FOUND,
                        "节点 " + currentNode + " 无审批人 " + approverId
                                + " 的待审批记录（非当前节点或无权限）"));

        int updated = approvalDao.updateResult(target.getId(), approverId,
                ApprovalResult.PENDING, decision, comment, LocalDateTime.now());
        if (updated == 0) {
            // 快照后又被并发决策：极端竞争，直接报冲突（同结果重放走上面的幂等分支）
            throw new SqlauditException(ErrorCode.APPROVAL_ALREADY_DECIDED,
                    "审批记录已被决策（并发冲突），ticket=" + ticketId + " node=" + currentNode);
        }

        // 重新加载，按节点组判定
        List<Approval> latest = approvalDao.listByTicket(ticketId);
        List<Approval> nodeRows = latest.stream()
                .filter(r -> r.getNodeOrder() == currentNode)
                .toList();
        ApprovalMode mode = nodeRows.get(0).getMode();
        List<Approval> effective = nodeRows.stream()
                .filter(r -> r.getResult() != ApprovalResult.TRANSFERRED)
                .toList();
        boolean anyApproved = effective.stream().anyMatch(r -> r.getResult() == ApprovalResult.APPROVED);
        boolean anyRejected = effective.stream().anyMatch(r -> r.getResult() == ApprovalResult.REJECTED);
        boolean anyPending = effective.stream().anyMatch(r -> r.getResult() == ApprovalResult.PENDING);

        boolean nodePassed;
        boolean nodeRejected;
        if (mode == ApprovalMode.AND) {
            // 会签：任一 REJECTED 即驳回；其余全部通过（APPROVED / SKIPPED）才通过
            nodeRejected = anyRejected;
            nodePassed = !anyRejected && !anyPending;
        } else {
            // 或签：任一 APPROVED 即通过；无人通过且有人驳回即驳回；
            // 全组只剩 SKIPPED（无人可审）视为通过
            nodePassed = anyApproved || (!anyApproved && !anyRejected && !anyPending);
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

        // 节点通过：还有后续节点（存在 PENDING）则等待（节点串行）
        boolean hasNextNode = latest.stream()
                .anyMatch(r -> r.getNodeOrder() > currentNode
                        && r.getResult() == ApprovalResult.PENDING);
        if (hasNextNode) {
            return new ApprovalDecision(ticketId, currentNode, approverId, decision, true, false, false);
        }

        // 全部节点通过 → 工单可转 APPROVED（审批 ≠ 执行，README §4.6）
        stateMachine.transition(ticket, TicketStatus.APPROVED, actor);
        return new ApprovalDecision(ticketId, currentNode, approverId, decision, true, true, false);
    }

    /** 该审批人能否决策这条记录（USER/INSTANCE_OWNER 看 ref=本人；ROLE 看持有角色）。 */
    private static boolean canDecide(Approval row, long approverId, List<String> myRoles) {
        if (row.getApproverType() == null || row.getApproverRef() == null) {
            return false; // 必需字段缺失一律拒绝（旧模型里 NULL 会跳过校验的越权口子已堵死）
        }
        return switch (row.getApproverType()) {
            case USER, INSTANCE_OWNER -> row.getApproverRef().equals(String.valueOf(approverId));
            case ROLE -> myRoles.contains(row.getApproverRef());
        };
    }

    // ------------------------------------------------------------------
    // 执行前二次确认（README §4.6）
    // ------------------------------------------------------------------

    /**
     * 执行前二次确认（CRITICAL 链 secondConfirm）。
     *
     * <p>不再做角色校验——谁能调本动作由接口权限点控制（{@code approval.confirm}）。
     * 硬校验：不能是提交人、工单必须在 approved 态。</p>
     *
     * @throws SqlauditException               code=SELF_CONFIRM_FORBIDDEN / TICKET_NOT_FOUND
     * @throws IllegalStateTransitionException 工单不在 approved 态
     */
    public void confirmExecution(long ticketId, long confirmerId) {
        Ticket ticket = requireTicket(ticketId);
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == confirmerId) {
            throw new SqlauditException(ErrorCode.SELF_CONFIRM_FORBIDDEN,
                    "提交人不得二次确认自己的工单（防自审，ticket=" + ticketId + "）");
        }
        TicketStatus status = ticket.getStatus();
        if (status != TicketStatus.APPROVED) {
            throw new IllegalStateTransitionException(ticket.getStatus().getCode(),
                    TicketStatus.EXECUTING.getCode(),
                    "二次确认仅对 approved 工单有效");
        }
        ticketDao.updateSecondConfirm(ticketId, confirmerId, LocalDateTime.now());
    }

    /** 该工单的审批链是否要求执行前二次确认。 */
    public boolean needsSecondConfirm(long ticketId) {
        return requireTicket(ticketId).isNeedsSecondConfirm();
    }

    // ------------------------------------------------------------------
    // 审批转交
    // ------------------------------------------------------------------

    /**
     * 审批转交（代理人）：把本人当前节点的待审批记录转给代理人。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>USER / INSTANCE_OWNER 记录：只有记录本人（ref = 本人）能转；</li>
     *   <li>ROLE 记录：持有该角色的任一审批人可转（转交即认领，decided_by = 转交人），
     *       代理人必须持有同一角色；</li>
     *   <li>代理人不能是提交人（防自审）、不能是本人、必须是 ACTIVE 用户、
     *       不能已在本组（否则新记录撞唯一键）；</li>
     *   <li>原记录置 TRANSFERRED + 新 USER 记录指向代理人，同一事务
     *       （{@link ApprovalDao#transfer}）。</li>
     * </ul>
     *
     * @throws SqlauditException code=INVALID_REQUEST / FORBIDDEN / TICKET_NOT_AUDITING /
     *                           NO_PENDING_APPROVAL / APPROVAL_ALREADY_DECIDED
     */
    public void transfer(long ticketId, long fromUserId, long toUserId) {
        Ticket ticket = requireTicket(ticketId);
        if (fromUserId == toUserId) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "转交目标不能是本人");
        }
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == toUserId) {
            throw new SqlauditException(ErrorCode.FORBIDDEN, "不能转交给提交人（防自审）");
        }
        if (ticket.getStatus() != TicketStatus.AUDITING) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_AUDITING,
                    "工单状态为 " + ticket.getStatus().getCode() + "，不在审批中");
        }
        User transferee = userDao.findById(toUserId);
        if (transferee == null || transferee.getStatus() != UserStatus.ACTIVE) {
            throw new SqlauditException(ErrorCode.FORBIDDEN, "代理人不存在或已停用");
        }

        List<Approval> rows = approvalDao.listByTicket(ticketId);
        int currentNode = currentGroup(rows);

        List<String> myRoles = roleCodesOf(fromUserId);
        Approval target = rows.stream()
                .filter(r -> r.getNodeOrder() == currentNode
                        && r.getResult() == ApprovalResult.PENDING
                        && canTransferBy(r, fromUserId, myRoles))
                .findFirst()
                .orElseThrow(() -> new SqlauditException(ErrorCode.NO_PENDING_APPROVAL,
                        "当前节点无本人（" + fromUserId + "）可转交的待审批记录"));

        // ROLE 记录：代理人须持有同一角色
        if (target.getApproverType() == ApproverType.ROLE) {
            String roleCode = target.getApproverRef();
            if (roleCode == null || !roleCodesOf(toUserId).contains(roleCode)) {
                throw new SqlauditException(ErrorCode.FORBIDDEN,
                        "代理人无「" + roleCode + "」角色，不能接管该审批节点");
            }
        }

        // 代理人已在本组 → 拒绝（新 USER 记录会撞唯一键，这里先给清晰错误）
        boolean alreadyInGroup = rows.stream()
                .anyMatch(r -> r.getNodeOrder() == currentNode
                        && (r.getApproverType() == ApproverType.USER
                            || r.getApproverType() == ApproverType.INSTANCE_OWNER)
                        && String.valueOf(toUserId).equals(r.getApproverRef()));
        if (alreadyInGroup) {
            throw new SqlauditException(ErrorCode.FORBIDDEN,
                    "代理人已在本审批组内，无需重复转交");
        }

        long newId = approvalDao.transfer(target.getId(), fromUserId, toUserId, LocalDateTime.now());
        if (newId == 0) {
            // 记录已被并发决策 / 转交，或代理人已入组（唯一键冲突整笔回滚）
            throw new SqlauditException(ErrorCode.APPROVAL_ALREADY_DECIDED,
                    "转交冲突：记录已被决策或代理人已入组，ticket=" + ticketId);
        }
    }

    /** 该审批人能否转交这条记录（USER/INSTANCE_OWNER 看 ref=本人；ROLE 看持有角色）。 */
    private static boolean canTransferBy(Approval row, long fromUserId, List<String> myRoles) {
        if (row.getApproverType() == null || row.getApproverRef() == null) {
            return false; // 必需字段缺失一律拒绝
        }
        return switch (row.getApproverType()) {
            case USER, INSTANCE_OWNER -> row.getApproverRef().equals(String.valueOf(fromUserId));
            case ROLE -> myRoles.contains(row.getApproverRef());
        };
    }

    // ------------------------------------------------------------------
    // 执行判断
    // ------------------------------------------------------------------

    /**
     * 工单当前是否允许进入执行（executor / worker 装配调用）：
     * approved / executing 且（无需二次确认 或 已确认）。
     */
    public boolean canExecute(long ticketId) {
        Ticket ticket = requireTicket(ticketId);
        TicketStatus status = ticket.getStatus();
        if (status != TicketStatus.APPROVED && status != TicketStatus.EXECUTING) {
            return false;
        }
        return !ticket.isNeedsSecondConfirm() || ticket.getConfirmedBy() != null;
    }

    // ------------------------------------------------------------------
    // 私有工具
    // ------------------------------------------------------------------

    /** 当前组 = 含 PENDING 记录的最小 nodeOrder。 */
    private static int currentGroup(List<Approval> rows) {
        return rows.stream()
                .filter(r -> ApprovalResult.PENDING == r.getResult())
                .mapToInt(Approval::getNodeOrder)
                .min()
                .orElseThrow(() -> new SqlauditException(ErrorCode.NO_PENDING_APPROVAL,
                        "工单没有待审批节点"));
    }

    /** 用户的角色 code 列表（每决策从库解析，角色变更即时生效）。 */
    private List<String> roleCodesOf(long userId) {
        List<String> codes = new ArrayList<>();
        for (Role role : roleDao.listByUserId(userId)) {
            if (role.getCode() != null) {
                codes.add(role.getCode());
            }
        }
        return codes;
    }

    /** 幂等重放：按工单当前状态重建返回值（不再触发状态迁移与节点判定）。 */
    private ApprovalDecision rebuildDecision(Ticket ticket, int nodeOrder, long approverId,
                                             ApprovalResult result) {
        TicketStatus status = ticket.getStatus();
        boolean approved = status == TicketStatus.APPROVED;
        boolean rejected = status == TicketStatus.REJECTED;
        return new ApprovalDecision(ticket.getId(), nodeOrder, approverId, result,
                approved, approved, rejected);
    }

    private Ticket requireTicket(long ticketId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_FOUND, "工单不存在：" + ticketId);
        }
        return ticket;
    }
}
