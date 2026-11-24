package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.server.service.TicketAccessService;
import com.sqlaudit.server.service.TicketSubmissionService;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.User;
import com.sqlaudit.workflow.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单端点（契约 §6 工单段）。
 *
 * <p>身份一律取登录态（{@link CurrentUser}），请求体不再出现 creatorId / environment；
 * 工单为<strong>单实例</strong>（{@code instanceId}）。「我的工单」/「全部工单」分开，
 * 后者要求 {@code GET /api/tickets/all} 能力且按实例数据范围过滤；详情走对象级访问
 * （{@link TicketAccessService}）。</p>
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    /** 能力判断用模式常量：查看全部工单（与 t_permission_api 的 url_pattern 一字不差）。 */
    public static final String API_GET_TICKETS_ALL = "/api/tickets/all";

    private final TicketSubmissionService submission;
    private final TicketService ticketService;
    private final TicketDao ticketDao;
    private final InstanceDao instanceDao;
    private final UserDao userDao;
    private final DataScopeService dataScopeService;
    private final TicketAccessService ticketAccessService;

    public TicketController(TicketSubmissionService submission, TicketService ticketService,
                            TicketDao ticketDao, InstanceDao instanceDao, UserDao userDao,
                            DataScopeService dataScopeService, TicketAccessService ticketAccessService) {
        this.submission = submission;
        this.ticketService = ticketService;
        this.ticketDao = ticketDao;
        this.instanceDao = instanceDao;
        this.userDao = userDao;
        this.dataScopeService = dataScopeService;
        this.ticketAccessService = ticketAccessService;
    }

    /** 提交预览：解析 → 规则 → 回滚清单 → 风险（无副作用，README §4.2 提交页第一步）。 */
    @PostMapping("/preview")
    public TicketSubmissionService.PreviewResult preview(@RequestBody PreviewRequest req,
                                                         HttpServletRequest request) {
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        dataScopeService.requireInScope(scope, req.instanceId());
        return submission.preview(req.sql(), req.dialect(), req.instanceId());
    }

    /** 建单并提交审核（提交即生成审批节点；ERROR 命中 / 审批链不可解析 → 400，不建单；整笔事务）。 */
    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public SubmitResponse submit(@RequestBody SubmitRequest req, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        dataScopeService.requireInScope(scope, req.instanceId());
        long id = submission.submit(req.title(), user.id(), req.instanceId(),
                req.sql(), req.shardConfig(), req.dialect());
        return new SubmitResponse(id);
    }

    /**
     * 我的工单（无参数，身份取登录态；分页 {@code page}/{@code size}，返回 {@code {items, total}}）。
     * page ≥ 1、size ≤ 200，越界钳制不报错（见 {@link PagedResponse}）。
     */
    @GetMapping
    public PagedResponse<TicketView> list(HttpServletRequest request,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = PagedResponse.DEFAULT_SIZE_STR) int size) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        int limit = PagedResponse.clampSize(size);
        int offset = PagedResponse.offsetOf(page, limit);
        List<TicketView> items = ticketDao.listByCreator(user.id(), offset, limit).stream()
                .map(this::viewOf).toList();
        long total = ticketDao.countByCreator(user.id());
        return PagedResponse.of(items, total);
    }

    /**
     * 全部工单（数据范围内；分页 {@code page}/{@code size}，返回 {@code {items, total}}）。
     * 数据范围 ALL → 全表分页；受限 → 按实例集合 DAO 分页（空集合返回空 + 0）。
     */
    @GetMapping("/all")
    public PagedResponse<TicketView> listAll(HttpServletRequest request,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = PagedResponse.DEFAULT_SIZE_STR) int size) {
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        int limit = PagedResponse.clampSize(size);
        int offset = PagedResponse.offsetOf(page, limit);
        List<Ticket> tickets;
        long total;
        if (scope.all()) {
            tickets = ticketDao.listAll(offset, limit);
            total = ticketDao.countAll();
        } else {
            List<Long> ids = List.copyOf(scope.ids());
            tickets = ticketDao.listByInstanceIds(ids, offset, limit);
            total = ticketDao.countByInstanceIds(ids);
        }
        return PagedResponse.of(tickets.stream().map(this::viewOf).toList(), total);
    }

    /** 工单详情（对象级访问，§4.5）。 */
    @GetMapping("/{id}")
    public TicketView get(@PathVariable long id, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        ticketAccessService.requireVisible(user, id);
        return viewOf(ticketService.getTicket(id));
    }

    /** 取消（无参数，仅提交人）。 */
    @PostMapping("/{id}/cancel")
    @org.springframework.transaction.annotation.Transactional
    public void cancel(@PathVariable long id, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        ticketAccessService.requireCreator(user, ticketService.getTicket(id));
        ticketService.cancel(id, String.valueOf(user.id()));
    }

    // ---------------- 视图 ----------------

    /** 工单 JSON（契约 §6：去 instanceIds / environment，加 creatorName / instanceName / instanceDefaultSchema）。 */
    public record TicketView(
            Long id, String title, Long creatorId, String creatorName,
            Long instanceId, String instanceName, String instanceDefaultSchema,
            RiskLevel riskLevel, TicketStatus status,
            String rollbackSummary, String sqlText, String shardConfig,
            boolean needsSecondConfirm, Long confirmedBy, LocalDateTime confirmedAt,
            String traceId, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private TicketView viewOf(Ticket t) {
        String creatorName = null;
        if (t.getCreatorId() != null) {
            User creator = userDao.findById(t.getCreatorId());
            creatorName = creator == null ? null : creator.getDisplayName();
        }
        String instanceName = null;
        String instanceDefaultSchema = null;
        if (t.getInstanceId() != null) {
            Instance instance = instanceDao.findById(t.getInstanceId());
            instanceName = instance == null ? null : instance.getName();
            instanceDefaultSchema = instance == null ? null : instance.getDefaultSchema();
        }
        return new TicketView(t.getId(), t.getTitle(), t.getCreatorId(), creatorName,
                t.getInstanceId(), instanceName, instanceDefaultSchema,
                t.getRiskLevel(), t.getStatus(),
                t.getRollbackSummary(), t.getSqlText(), t.getShardConfig(),
                t.isNeedsSecondConfirm(), t.getConfirmedBy(), t.getConfirmedAt(),
                t.getTraceId(), t.getCreatedAt(), t.getUpdatedAt());
    }

    // ---------------- DTO ----------------

    /** dialect 按存储码（MYSQL / PG / IOTDB）反序列化，未知取值由统一错误出口转 400。 */
    public record PreviewRequest(String sql, Dialect dialect, long instanceId) {
    }

    public record SubmitRequest(String title, long instanceId, String sql, String shardConfig,
                                Dialect dialect) {
    }

    public record SubmitResponse(long ticketId) {
    }
}
