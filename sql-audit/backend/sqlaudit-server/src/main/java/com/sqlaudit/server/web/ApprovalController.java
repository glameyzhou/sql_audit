package com.sqlaudit.server.web;

import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.workflow.ApprovalDecision;
import com.sqlaudit.workflow.ApprovalFlowEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批端点。防自审在 {@link ApprovalFlowEngine#submitDecision} 硬校验（§3.4）。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalFlowEngine approvalEngine;
    private final ApprovalDao approvalDao;

    public ApprovalController(ApprovalFlowEngine approvalEngine, ApprovalDao approvalDao) {
        this.approvalEngine = approvalEngine;
        this.approvalDao = approvalDao;
    }

    /** 待审批列表（W2 按角色过滤；approver_id=0 表示按角色解析的节点）。 */
    @GetMapping("/pending")
    public List<com.sqlaudit.storage.model.Approval> pending() {
        return approvalDao.listPending();
    }

    /** 审批决策（approve/reject/comment；approverId W2 改从 JWT 取）。 */
    @PostMapping("/{ticketId}/decide")
    public ApprovalDecision decide(@PathVariable long ticketId, @RequestBody DecideRequest req) {
        return approvalEngine.submitDecision(ticketId, req.approverId(), req.approve(), req.comment());
    }

    /** 二次确认（CRITICAL 工单执行前，§3.4）。 */
    @PostMapping("/{ticketId}/confirm-execution")
    public void confirmExecution(@PathVariable long ticketId, @RequestBody ConfirmRequest req) {
        approvalEngine.confirmExecution(ticketId, req.confirmerId());
    }

    public record DecideRequest(long approverId, boolean approve, String comment) {
    }

    public record ConfirmRequest(long confirmerId) {
    }
}
