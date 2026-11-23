package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.server.auth.RequireRole;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.TicketUnit;
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

import java.util.List;

/**
 * 审批端点。审批人 / 二次确认人的真实身份从 JWT 取（拦截器写入 request attribute），
 * 不再信任请求体；防自审与角色校验在 {@link ApprovalFlowEngine} 硬校验（§3.4 / §8.2）。
 *
 * <p><strong>§3.5 表结构指纹：审批时记录、执行前比对。</strong>审批通过（approve=true）
 * 时，在决策落库<strong>之前</strong>对工单的每个执行单元采集目标表当前结构指纹
 * （{@code SHA256(SHOW CREATE TABLE)}，经 {@link ExecutionGate.StructureFingerprintProvider}
 * 连 RO_META 只读凭据取），写入 {@code t_ticket_unit.ddl_fingerprint}；执行前由 worker
 * 侧重算比对（R_SCHEMA_DRIFT 门禁）。采集失败（凭据/连接问题）抛
 * {@code FINGERPRINT_CAPTURE_FAILED} 阻断审批，保证决策未落库、无副作用——宁可
 * 审批打回，也不放过「审核后、执行前表结构漂移」的窗口。</p>
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalFlowEngine approvalEngine;
    private final ApprovalDao approvalDao;
    private final TicketUnitDao ticketUnitDao;
    private final ExecutionGate.StructureFingerprintProvider structureFingerprintProvider;

    public ApprovalController(ApprovalFlowEngine approvalEngine, ApprovalDao approvalDao,
                              TicketUnitDao ticketUnitDao,
                              ExecutionGate.StructureFingerprintProvider structureFingerprintProvider) {
        this.approvalEngine = approvalEngine;
        this.approvalDao = approvalDao;
        this.ticketUnitDao = ticketUnitDao;
        this.structureFingerprintProvider = structureFingerprintProvider;
    }

    /**
     * 待我审批列表：按登录用户过滤——指派给本人（approver_id=本人）或 ROLE 占位节点
     * （approver_id=0）且本人持有对应角色（approver_ref）。
     */
    @GetMapping("/pending")
    @RequireRole
    public List<Approval> pending(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
        return approvalDao.listPending().stream()
                .filter(a -> visibleTo(a, userId, roles))
                .toList();
    }

    /**
     * 审批决策（approve/reject/comment）。审批人身份取 JWT，角色校验在
     * {@link ApprovalFlowEngine#submitDecision}（ROLE 节点须持有 approver_ref 角色）。
     */
    @PostMapping("/{ticketId}/decide")
    @RequireRole
    public ApprovalDecision decide(@PathVariable long ticketId, @RequestBody DecideRequest req,
                                   HttpServletRequest request) {
        Long approverId = requireUserId(request);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
        if (req.approve()) {
            captureStructureFingerprints(ticketId);
        }
        return approvalEngine.submitDecision(ticketId, approverId, roles, req.approve(), req.comment());
    }

    /** 执行前二次确认（CRITICAL 工单，§3.4；角色校验 DBA/ADMIN 在引擎层）。 */
    @PostMapping("/{ticketId}/confirm-execution")
    @RequireRole
    public void confirmExecution(@PathVariable long ticketId, HttpServletRequest request) {
        Long confirmerId = requireUserId(request);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
        approvalEngine.confirmExecution(ticketId, confirmerId, roles);
    }

    /** 审批转交（代理人）：当前审批人把待审批记录转给代理人。 */
    @PostMapping("/{ticketId}/transfer")
    @RequireRole
    public void transfer(@PathVariable long ticketId, @RequestBody TransferRequest req,
                         HttpServletRequest request) {
        Long fromUserId = requireUserId(request);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
        approvalEngine.transfer(ticketId, fromUserId, req.toUserId(), roles);
    }

    // ---------------- 工具 ----------------

    /** 待审批记录是否对当前用户可见（本人指派 或 本人持有对应角色）。 */
    private static boolean visibleTo(Approval a, Long userId, List<String> roles) {
        if (userId != null && a.getApproverId() != null && a.getApproverId() == userId) {
            return true;
        }
        return a.getApproverId() != null && a.getApproverId() == 0L
                && a.getApproverRef() != null && roles != null && roles.contains(a.getApproverRef());
    }

    private static Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            throw new SqlauditException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    /**
     * §3.5 审批时采集表结构指纹（在决策落库之前执行，失败即阻断、无副作用）。
     */
    private void captureStructureFingerprints(long ticketId) {
        for (TicketUnit unit : ticketUnitDao.listByTicket(ticketId)) {
            // DDL 对象此刻尚不存在（执行后才有），无法采集；留空即可，执行期门禁自动跳过
            if (unit.getStatementType() == StatementType.DDL) {
                continue;
            }
            if (unit.getSchemaName() == null || unit.getObjectName() == null) {
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
