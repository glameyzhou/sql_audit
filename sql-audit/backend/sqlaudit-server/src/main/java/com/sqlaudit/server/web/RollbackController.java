package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.server.service.RollbackExecutor;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.TicketUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 回滚管理端点（契约 §6 回滚）。列表按<strong>实例数据范围</strong>过滤；反向语句明细
 * （含前镜像旧值 = 生产数据）与执行按实例校验（{@code INSTANCE_OUT_OF_SCOPE} 403）。
 * 操作人取登录态（{@link CurrentUser}），审计写真实身份。</p>
 */
@RestController
@RequestMapping("/api/rollbacks")
public class RollbackController {

    private final RollbackDao rollbackDao;
    private final RollbackExecutor rollbackExecutor;
    private final TicketUnitDao ticketUnitDao;
    private final DataScopeService dataScopeService;

    public RollbackController(RollbackDao rollbackDao, RollbackExecutor rollbackExecutor,
                              TicketUnitDao ticketUnitDao, DataScopeService dataScopeService) {
        this.rollbackDao = rollbackDao;
        this.rollbackExecutor = rollbackExecutor;
        this.ticketUnitDao = ticketUnitDao;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 回滚管理：数据范围内的回滚主记录（按 id 倒序，最新在前；ticketId 供前端跳转工单详情）。
     *
     * <p>分页 {@code page}/{@code size}（钳制见 {@link PagedResponse}），返回 {@code {items, total}}；
     * 可选 {@code ticketId} 按工单过滤（供工单详情页做「单元 → 回滚记录」映射）。</p>
     *
     * <p><strong>过滤在内存做，过滤后再分页</strong>：回滚主记录须经 unit 解析实例归属
     * （数据范围）与工单归属（ticketId 过滤），DAO 层没有对应下推方法；按「回滚记录数」量级
     * 可接受。数据范围过滤保持既有语义（unit 缺失视为越权丢弃）。</p>
     */
    @GetMapping
    public PagedResponse<RollbackDto> list(HttpServletRequest request,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = PagedResponse.DEFAULT_SIZE_STR) int size,
                                           @RequestParam(required = false) Long ticketId) {
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        List<RollbackDto> filtered = new ArrayList<>();
        for (RollbackSql rollback : rollbackDao.listAll()) {
            TicketUnit unit = resolveUnit(rollback);
            if (!inScope(unit, scope)) {
                continue;
            }
            if (ticketId != null && (unit == null || unit.getTicketId() == null
                    || !ticketId.equals(unit.getTicketId()))) {
                continue;
            }
            filtered.add(RollbackDto.from(rollback, unit));
        }
        int limit = PagedResponse.clampSize(size);
        int from = Math.min(PagedResponse.offsetOf(page, limit), filtered.size());
        int to = Math.min(from + limit, filtered.size());
        return PagedResponse.of(List.copyOf(filtered.subList(from, to)), filtered.size());
    }

    /** 执行回滚（rollback.execute 权限点）：把反向语句真正应用到生产库。 */
    @PostMapping("/{id}/execute")
    public RollbackExecutor.Result execute(@PathVariable long id, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        requireInScopeOf(id, dataScopeService.requestScope(request));
        AuditActor actor = AuditActor.of(user, request);
        return rollbackExecutor.executeRollback(id, actor);
    }

    /** 反向语句明细（展示用，拼字面量 SQL；含前镜像旧值，按实例校验）。 */
    @GetMapping("/{id}/statements")
    public List<StatementDto> statements(@PathVariable long id, HttpServletRequest request) {
        requireInScopeOf(id, dataScopeService.requestScope(request));
        return rollbackDao.listStatements(id, 0, -1).stream()
                .map(s -> StatementDto.from(s, rollbackExecutor.renderStatementSql(s)))
                .toList();
    }

    /** 经 unit → instance 解析回滚主记录关联的执行单元；unitId 缺失或查不到返回 null。 */
    private TicketUnit resolveUnit(RollbackSql rollback) {
        if (rollback.getUnitId() == null) {
            return null;
        }
        return ticketUnitDao.findById(rollback.getUnitId());
    }

    /** 回滚主记录是否落在数据范围内（unit 缺失视为越权）。 */
    private boolean inScope(TicketUnit unit, DataScopeService.Scope scope) {
        return unit != null && unit.getInstanceId() != null && scope.covers(unit.getInstanceId());
    }

    private void requireInScopeOf(long rollbackId, DataScopeService.Scope scope) {
        RollbackSql rollback = rollbackDao.findById(rollbackId);
        if (rollback == null) {
            return; // 不存在的记录由执行器报 ROLLBACK_NOT_FOUND
        }
        if (!inScope(resolveUnit(rollback), scope)) {
            throw new com.sqlaudit.common.exception.SqlauditException(
                    com.sqlaudit.common.enums.ErrorCode.INSTANCE_OUT_OF_SCOPE,
                    "回滚记录 " + rollbackId + " 关联的实例不在当前用户的数据范围内");
        }
    }

    /**
     * 回滚主记录投影：仅暴露前端所需关键字段（generator / status / coverage 按存储码序列化）。
     * {@code ticketId} 经 unit → ticket 解析，供前端「工单详情」跳转（历史脏数据可能为 null）。
     */
    public record RollbackDto(Long id, Long unitId, Long ticketId, RollbackGenerator generator, RollbackStatus status,
                              RollbackCoverage coverage, LocalDateTime expiresAt, LocalDateTime createdAt) {
        static RollbackDto from(RollbackSql r, TicketUnit unit) {
            return new RollbackDto(r.getId(), r.getUnitId(),
                    unit == null ? null : unit.getTicketId(),
                    r.getGenerator(), r.getStatus(),
                    r.getCoverage(), r.getExpiresAt(), r.getCreatedAt());
        }
    }

    /** 反向语句投影（展示用）。 */
    public record StatementDto(Long id, Long rollbackId, Integer stmtOrder, String sql,
                               LocalDateTime appliedAt) {
        static StatementDto from(RollbackStatement s, String sql) {
            return new StatementDto(s.getId(), s.getRollbackId(), s.getStmtOrder(), sql,
                    s.getAppliedAt());
        }
    }
}
