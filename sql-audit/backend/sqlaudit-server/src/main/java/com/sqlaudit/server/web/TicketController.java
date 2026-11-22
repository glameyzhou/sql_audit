package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.server.service.TicketSubmissionService;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.workflow.TicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单端点（契约对齐 frontend/src/api/index.js）。
 *
 * <p>鉴权未启用：creatorId 由请求传入，W2 接 JWT 后改从安全上下文取。</p>
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketSubmissionService submission;
    private final TicketService ticketService;
    private final TicketDao ticketDao;

    public TicketController(TicketSubmissionService submission, TicketService ticketService,
                            TicketDao ticketDao) {
        this.submission = submission;
        this.ticketService = ticketService;
        this.ticketDao = ticketDao;
    }

    /** 提交预览：解析 → 规则 → 回滚清单 → 风险（无副作用，§3.1 提交页第一步）。 */
    @PostMapping("/preview")
    public TicketSubmissionService.PreviewResult preview(@RequestBody PreviewRequest req) {
        return submission.preview(req.sql(), Dialect.valueOf(req.dialect()), req.instanceIds());
    }

    /** 建单并提交审核（ERROR 命中 → 400 ERROR_RULE_BLOCKED，不建单）。 */
    @PostMapping
    public SubmitResponse submit(@RequestBody SubmitRequest req) {
        long id = submission.submit(req.title(), req.creatorId(), req.instanceIds(),
                req.environment(), req.sql(), Dialect.valueOf(req.dialect()));
        return new SubmitResponse(id);
    }

    /** 我的工单。 */
    @GetMapping
    public List<Ticket> list(@RequestParam long creatorId) {
        return ticketDao.listByCreator(creatorId);
    }

    /** 工单详情。 */
    @GetMapping("/{id}")
    public Ticket get(@PathVariable long id) {
        return ticketService.getTicket(id);
    }

    /** 提交审批（影响面评估完成后，§4.4）。 */
    @PostMapping("/{id}/submit-approval")
    public void submitApproval(@PathVariable long id, @RequestBody SubmitApprovalRequest req) {
        ticketService.submitForApproval(id, req.impactAssessmentDone());
    }

    /** 取消。 */
    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable long id, @RequestParam long actorId) {
        ticketService.cancel(id, String.valueOf(actorId));
    }

    // ---------------- DTO ----------------

    public record PreviewRequest(String sql, String dialect, List<Long> instanceIds) {
    }

    public record SubmitRequest(String title, long creatorId, List<Long> instanceIds,
                                String environment, String sql, String dialect) {
    }

    public record SubmitApprovalRequest(boolean impactAssessmentDone) {
    }

    public record SubmitResponse(long ticketId) {
    }
}
