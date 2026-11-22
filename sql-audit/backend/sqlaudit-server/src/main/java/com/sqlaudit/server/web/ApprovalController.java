package com.sqlaudit.server.web;

import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.workflow.ApprovalDecision;
import com.sqlaudit.workflow.ApprovalFlowEngine;
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
 * 审批端点。防自审在 {@link ApprovalFlowEngine#submitDecision} 硬校验（§3.4）。
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

    /** 待审批列表（W2 按角色过滤；approver_id=0 表示按角色解析的节点）。 */
    @GetMapping("/pending")
    public List<com.sqlaudit.storage.model.Approval> pending() {
        return approvalDao.listPending();
    }

    /**
     * 审批决策（approve/reject/comment；approverId W2 改从 JWT 取）。
     *
     * <p>approve=true 时先采集 §3.5 表结构指纹（见类 javadoc），再落决策；
     * 采集失败直接 400，工单保持 auditing 未动。</p>
     */
    @PostMapping("/{ticketId}/decide")
    public ApprovalDecision decide(@PathVariable long ticketId, @RequestBody DecideRequest req) {
        if (req.approve()) {
            captureStructureFingerprints(ticketId);
        }
        return approvalEngine.submitDecision(ticketId, req.approverId(), req.approve(), req.comment());
    }

    /** 二次确认（CRITICAL 工单执行前，§3.4）。 */
    @PostMapping("/{ticketId}/confirm-execution")
    public void confirmExecution(@PathVariable long ticketId, @RequestBody ConfirmRequest req) {
        approvalEngine.confirmExecution(ticketId, req.confirmerId());
    }

    /**
     * §3.5 审批时采集表结构指纹（在决策落库之前执行，失败即阻断、无副作用）。
     *
     * <p>逐 unit 处理：</p>
     * <ul>
     *   <li>DDL unit 跳过——对象在审批时点尚不存在（执行后才有），无法采集，
     *       ddl_fingerprint 留空则执行期 drift 门禁自动跳过；</li>
     *   <li>schemaName / objectName 为 null 的 unit 跳过（无从定位目标表）；</li>
     *   <li>provider 返回 null（表不存在等场景）→ log.warn 后跳过，指纹留空；</li>
     *   <li>provider 抛 {@link IllegalStateException}（连接/凭据问题）→ 包成
     *       {@code FINGERPRINT_CAPTURE_FAILED} 抛出，审批整体失败。</li>
     * </ul>
     */
    private void captureStructureFingerprints(long ticketId) {
        for (TicketUnit unit : ticketUnitDao.listByTicket(ticketId)) {
            // DDL 对象此刻尚不存在（执行后才有），无法采集；留空即可，执行期门禁自动跳过
            if ("DDL".equals(unit.getStatementType())) {
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
                throw new SqlauditException("FINGERPRINT_CAPTURE_FAILED",
                        "审批前表结构指纹采集失败，请检查实例 " + unit.getInstanceId()
                                + " 的 RO_META 凭据: " + e.getMessage(), e);
            }
        }
    }

    public record DecideRequest(long approverId, boolean approve, String comment) {
    }

    public record ConfirmRequest(long confirmerId) {
    }
}
