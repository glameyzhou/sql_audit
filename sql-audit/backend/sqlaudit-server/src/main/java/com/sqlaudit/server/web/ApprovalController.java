package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.storage.model.User;
import com.sqlaudit.workflow.ApprovalDecision;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批端点（契约 §6 审批段）。审批人身份取登录态（拦截器写入 request attribute），
 * 请求体只有决策 / 转交目标；防自审与角色校验在 {@link ApprovalFlowEngine} 硬校验
 * （引擎每决策从库解析角色，不接收调用方传的角色快照）。
 *
 * <p>待审列表只显示<strong>当前组、本人可审、非本人提交</strong>的记录（契约 §6）；
 * 待审资格由审批链做真正的门禁（{@code basic}（all_users）权限点放行端点本身）。</p>
 *
 * <p><strong>README §4.2 表结构指纹：审批时记录、执行前比对。</strong>审批通过（approve=true）
 * 时，在决策落库<strong>之前</strong>对工单的每个执行单元采集目标表当前结构指纹，写入
 * {@code t_ticket_unit.ddl_fingerprint}；采集失败抛 {@code FINGERPRINT_CAPTURE_FAILED}
 * 阻断审批，保证决策未落库、无副作用。</p>
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalFlowEngine approvalEngine;
    private final ApprovalDao approvalDao;
    private final TicketDao ticketDao;
    private final TicketUnitDao ticketUnitDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final ExecutionGate.StructureFingerprintProvider structureFingerprintProvider;
    private final InstanceDao instanceDao;
    private final DataScopeService dataScopeService;

    public ApprovalController(ApprovalFlowEngine approvalEngine, ApprovalDao approvalDao,
                              TicketDao ticketDao, TicketUnitDao ticketUnitDao,
                              UserDao userDao, RoleDao roleDao,
                              ExecutionGate.StructureFingerprintProvider structureFingerprintProvider,
                              InstanceDao instanceDao, DataScopeService dataScopeService) {
        this.approvalEngine = approvalEngine;
        this.approvalDao = approvalDao;
        this.ticketDao = ticketDao;
        this.ticketUnitDao = ticketUnitDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.structureFingerprintProvider = structureFingerprintProvider;
        this.instanceDao = instanceDao;
        this.dataScopeService = dataScopeService;
    }

    /** 待我审批列表：仅当前组、本人可审、非本人提交（契约 §6）。 */
    @GetMapping("/pending")
    public List<PendingView> pending(HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        List<PendingView> out = new ArrayList<>();
        for (Approval a : approvalDao.listPending()) {
            Ticket ticket = ticketDao.findById(a.getTicketId());
            if (ticket == null) {
                continue;
            }
            if (ticket.getCreatorId() != null && ticket.getCreatorId() == user.id()) {
                continue; // 非本人提交
            }
            if (a.getNodeOrder() == null || !isCurrentGroup(ticket.getId(), a.getNodeOrder())) {
                continue; // 仅当前组
            }
            if (!actionableBy(a, user)) {
                continue; // 本人可审
            }
            out.add(toView(a, ticket));
        }
        return out;
    }

    /** 审批决策（approve/reject/comment；先鉴权与防自审，通过后再采指纹，整笔事务失败回滚）。 */
    @PostMapping("/{ticketId}/decide")
    @org.springframework.transaction.annotation.Transactional
    public ApprovalDecision decide(@PathVariable long ticketId, @RequestBody DecideRequest req,
                                   HttpServletRequest request) {
        long approverId = RoleAuthInterceptor.currentUser(request).id();
        ApprovalDecision decision = approvalEngine.submitDecision(
                ticketId, approverId, req.approve(), req.comment());
        if (req.approve()) {
            captureStructureFingerprints(ticketId);
        }
        return decision;
    }

    /** 执行前二次确认（CRITICAL 工单，README §4.6；校验实例数据范围与防自审）。 */
    @PostMapping("/{ticketId}/confirm-execution")
    public void confirmExecution(@PathVariable long ticketId, HttpServletRequest request) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket != null && ticket.getInstanceId() != null) {
            dataScopeService.requireInScope(dataScopeService.requestScope(request), ticket.getInstanceId());
        }
        long confirmerId = RoleAuthInterceptor.currentUser(request).id();
        approvalEngine.confirmExecution(ticketId, confirmerId);
    }

    /** 审批转交（代理人）：当前审批人把待审批记录转给代理人。 */
    @PostMapping("/{ticketId}/transfer")
    public void transfer(@PathVariable long ticketId, @RequestBody TransferRequest req,
                         HttpServletRequest request) {
        long fromUserId = RoleAuthInterceptor.currentUser(request).id();
        approvalEngine.transfer(ticketId, fromUserId, req.toUserId());
    }

    // ---------------- 视图 ----------------

    /** 待审记录视图（契约 §6 审批段字段）。 */
    public record PendingView(
            Long id, Long ticketId, String ticketTitle, RiskLevel riskLevel,
            Long creatorId, String creatorName, Integer nodeOrder, ApprovalMode mode,
            ApproverType approverType, String approverRef, String approverDisplay,
            LocalDateTime createdAt) {
    }

    private PendingView toView(Approval a, Ticket ticket) {
        String creatorName = null;
        if (ticket.getCreatorId() != null) {
            User creator = userDao.findById(ticket.getCreatorId());
            creatorName = creator == null ? null : creator.getDisplayName();
        }
        return new PendingView(a.getId(), a.getTicketId(), ticket.getTitle(), ticket.getRiskLevel(),
                ticket.getCreatorId(), creatorName, a.getNodeOrder(), a.getMode(),
                a.getApproverType(), a.getApproverRef(), approverDisplay(a), a.getCreatedAt());
    }

    private String approverDisplay(Approval a) {
        if (a.getApproverType() == null || a.getApproverRef() == null) {
            return null;
        }
        return switch (a.getApproverType()) {
            case USER, INSTANCE_OWNER -> {
                try {
                    User u = userDao.findById(Long.parseLong(a.getApproverRef()));
                    yield u == null ? null : u.getDisplayName();
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case ROLE -> {
                Role role = roleDao.findByCode(a.getApproverRef());
                yield role == null ? null : role.getName();
            }
        };
    }

    // ---------------- 工具 ----------------

    /** 该组是否为工单当前组（含 PENDING 记录的最小 nodeOrder）。 */
    private boolean isCurrentGroup(long ticketId, int nodeOrder) {
        int min = approvalDao.listByTicket(ticketId).stream()
                .filter(r -> r.getResult() == ApprovalResult.PENDING && r.getNodeOrder() != null)
                .mapToInt(Approval::getNodeOrder)
                .min()
                .orElseThrow(() -> new SqlauditException(ErrorCode.NO_PENDING_APPROVAL,
                        "工单没有待审批节点"));
        return nodeOrder == min;
    }

    /** 本人能否决策这条记录（USER/INSTANCE_OWNER 看 ref=本人；ROLE 看持有角色）。 */
    private static boolean actionableBy(Approval a, CurrentUser user) {
        if (a.getApproverType() == null || a.getApproverRef() == null) {
            return false; // 必需字段缺失一律不可审
        }
        return switch (a.getApproverType()) {
            case USER, INSTANCE_OWNER -> a.getApproverRef().equals(String.valueOf(user.id()));
            case ROLE -> user.roleCodes().contains(a.getApproverRef());
        };
    }

    /** 目标实例是否为 IoTDB 方言（IoTDB 白名单 parser 的对象路径整体在 objectName，schemaName 恒 null）。 */
    private boolean isIotdbInstance(long instanceId) {
        Instance inst = instanceDao.findById(instanceId);
        return inst != null && inst.getDialect() == Dialect.IOTDB;
    }

    /**
     * README §4.2 审批时采集表结构指纹（在决策落库之前执行，失败即阻断、无副作用）。
     */
    private void captureStructureFingerprints(long ticketId) {
        for (TicketUnit unit : ticketUnitDao.listByTicket(ticketId)) {
            // DDL 对象此刻尚不存在（执行后才有），无法采集；留空即可，执行期门禁自动跳过
            if (unit.getStatementType() == StatementType.DDL) {
                continue;
            }
            if (unit.getObjectName() == null) {
                continue;
            }
            // IoTDB 白名单 parser 把对象路径整体放 objectName（schemaName 恒 null）；
            // 其余方言 schemaName 缺失时对象无法唯一定位，跳过。
            if (unit.getSchemaName() == null && !isIotdbInstance(unit.getInstanceId())) {
                continue;
            }
            try {
                String fingerprint = structureFingerprintProvider.currentFingerprint(
                        unit.getInstanceId(), unit.getSchemaName(), unit.getObjectName());
                if (fingerprint == null) {
                    log.warn("审批表结构指纹采集跳过（表不存在等场景）: instanceId={}, schema={}, table={}",
                            unit.getInstanceId(), unit.getSchemaName(), unit.getObjectName());
                    continue;
                }
                ticketUnitDao.updateDdlFingerprint(unit.getId(), fingerprint);
            } catch (IllegalStateException e) {
                throw new SqlauditException(ErrorCode.FINGERPRINT_CAPTURE_FAILED,
                        "审批前表结构指纹采集失败，请检查实例 " + unit.getInstanceId()
                                + " 的 RO_META 凭据: " + e.getMessage(), e);
            }
        }
    }

    public record DecideRequest(boolean approve, String comment) {
    }

    public record TransferRequest(long toUserId) {
    }
}
