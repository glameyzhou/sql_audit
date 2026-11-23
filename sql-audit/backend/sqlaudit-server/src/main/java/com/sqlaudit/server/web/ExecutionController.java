package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行监控端点（对齐 frontend/src/api/index.js 的 listExecutions / listTicketUnits）。
 *
 * <p>只读投影：{@code statement} 为 AES-GCM 密文（VARBINARY），不外泄，故映射为
 * 精简 DTO 而非直接返回 {@link TicketUnit}。</p>
 */
@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final TicketUnitDao ticketUnitDao;

    public ExecutionController(TicketUnitDao ticketUnitDao) {
        this.ticketUnitDao = ticketUnitDao;
    }

    /**
     * 执行监控：按执行单元状态列表；status 缺省默认 executing。
     *
     * <p>{@code status} 按存储码绑定（executing / pending / leased / ...，大小写不敏感，
     * 见 {@code CodedEnumParamBindingConfig}）；不在合法集合内的取值由统一错误出口转 400。</p>
     */
    @GetMapping
    public List<UnitDto> listByStatus(@RequestParam(required = false) UnitStatus status) {
        UnitStatus effective = status == null ? UnitStatus.EXECUTING : status;
        return ticketUnitDao.listByStatus(effective).stream().map(UnitDto::from).toList();
    }

    /** 工单详情：某工单的全部执行单元（按 unit_order 稳定排序）。 */
    @GetMapping("/ticket/{ticketId}")
    public List<UnitDto> listByTicket(@PathVariable long ticketId) {
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
