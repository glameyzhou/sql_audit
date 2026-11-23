package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.server.service.TicketAccessService;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行监控端点（契约 §6 执行监控）。
 *
 * <p>{@code GET /api/executions}（{@code execution.view} 权限点）按<strong>实例数据范围</strong>
 * 过滤；{@code GET /api/executions/ticket/{ticketId}}（{@code basic}）走工单对象级访问
 * （{@link TicketAccessService}，§4.5）。只读投影：{@code statement} 为密文，
 * 不外泄，故映射为精简 DTO 而非直接返回 {@link TicketUnit}。</p>
 */
@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final TicketUnitDao ticketUnitDao;
    private final DataScopeService dataScopeService;
    private final TicketAccessService ticketAccessService;

    public ExecutionController(TicketUnitDao ticketUnitDao, DataScopeService dataScopeService,
                               TicketAccessService ticketAccessService) {
        this.ticketUnitDao = ticketUnitDao;
        this.dataScopeService = dataScopeService;
        this.ticketAccessService = ticketAccessService;
    }

    /**
     * 执行监控：按执行单元状态列表；status 缺省默认 executing。
     *
     * <p>{@code status} 按存储码绑定（executing / pending / leased / ...，大小写不敏感，
     * 见 {@code CodedEnumParamBindingConfig}）；不在合法集合内的取值由统一错误出口转 400。</p>
     */
    @GetMapping
    public List<UnitDto> listByStatus(@RequestParam(required = false) UnitStatus status,
                                      HttpServletRequest request) {
        UnitStatus effective = status == null ? UnitStatus.EXECUTING : status;
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        return ticketUnitDao.listByStatus(effective).stream()
                .filter(u -> u.getInstanceId() != null && scope.covers(u.getInstanceId()))
                .map(UnitDto::from)
                .toList();
    }

    /** 工单详情：某工单的全部执行单元（按 unit_order 稳定排序；对象级访问）。 */
    @GetMapping("/ticket/{ticketId}")
    public List<UnitDto> listByTicket(@PathVariable long ticketId, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        ticketAccessService.requireVisible(user, ticketId);
        return ticketUnitDao.listByTicket(ticketId).stream().map(UnitDto::from).toList();
    }

    /** 执行单元投影：仅暴露前端所需关键字段（statementType / status 按存储码序列化）。 */
    public record UnitDto(Long id, Long ticketId, Long instanceId, StatementType statementType,
                          UnitStatus status, String leaseOwner, LocalDateTime executedAt,
                          Long affectedRows, String errorMessage) {
        static UnitDto from(TicketUnit u) {
            return new UnitDto(u.getId(), u.getTicketId(), u.getInstanceId(), u.getStatementType(),
                    u.getStatus(), u.getLeaseOwner(), u.getExecutedAt(), u.getAffectedRows(),
                    u.getErrorMessage());
        }
    }
}
