package com.sqlaudit.server.service;

import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.PermissionService;
import com.sqlaudit.server.web.TicketController;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Ticket;
import org.springframework.stereotype.Service;

/**
 * 工单对象级访问（契约 §4.5）：功能权限在路由层回答「能不能调这个接口」，
 * 本服务回答「在这个工单对象上能不能」。
 *
 * <p>可见性规则（{@code GET /api/tickets/{id}}、{@code GET /api/executions/ticket/{ticketId}}）：</p>
 * <ol>
 *   <li>提交人本人；</li>
 *   <li>该工单任一审批记录对其「可审 / 已审」——USER / INSTANCE_OWNER 的 ref = 本人，
 *       ROLE 的 ref ∈ 本人角色，或 decided_by = 本人；</li>
 *   <li>持有「查看全部工单」能力（{@link PermissionService#canCall}，
 *       {@code GET /api/tickets/all}）且工单实例在其数据范围内。</li>
 * </ol>
 *
 * <p>不满足 → {@code TICKET_ACCESS_DENIED}（403）。「取消」仅提交人
 * （{@link #requireCreator}）。</p>
 */
@Service
public class TicketAccessService {

    private final TicketDao ticketDao;
    private final ApprovalDao approvalDao;
    private final PermissionService permissionService;
    private final DataScopeService dataScopeService;

    public TicketAccessService(TicketDao ticketDao, ApprovalDao approvalDao,
                               PermissionService permissionService, DataScopeService dataScopeService) {
        this.ticketDao = ticketDao;
        this.approvalDao = approvalDao;
        this.permissionService = permissionService;
        this.dataScopeService = dataScopeService;
    }

    /** 工单是否对当前用户可见；不可见抛 {@code TICKET_ACCESS_DENIED}（403）。 */
    public void requireVisible(CurrentUser user, Ticket ticket) {
        if (ticket.getCreatorId() != null && ticket.getCreatorId() == user.id()) {
            return; // 提交人
        }
        for (Approval a : approvalDao.listByTicket(ticket.getId())) {
            if (targetsCaller(a, user)) {
                return; // 可审 / 已审
            }
        }
        // 持有「查看全部工单」能力且实例在数据范围内
        if (permissionService.canCall(user, "GET", TicketController.API_GET_TICKETS_ALL)) {
            DataScopeService.Scope scope = dataScopeService.scopeOf(
                    permissionService.decide(user, "GET", TicketController.API_GET_TICKETS_ALL)
                            .authorizingRoleIds());
            if (ticket.getInstanceId() != null && scope.covers(ticket.getInstanceId())) {
                return;
            }
        }
        throw new SqlauditException(ErrorCode.TICKET_ACCESS_DENIED, "无权查看该工单");
    }

    /** 按 id 校验可见性（工单不存在抛 TICKET_NOT_FOUND）。 */
    public void requireVisible(CurrentUser user, long ticketId) {
        Ticket ticket = ticketDao.findById(ticketId);
        if (ticket == null) {
            throw new SqlauditException(ErrorCode.TICKET_NOT_FOUND, "工单不存在: " + ticketId);
        }
        requireVisible(user, ticket);
    }

    /** 仅提交人可操作（取消）；不满足抛 {@code TICKET_ACCESS_DENIED}（403）。 */
    public void requireCreator(CurrentUser user, Ticket ticket) {
        if (ticket.getCreatorId() == null || ticket.getCreatorId() != user.id()) {
            throw new SqlauditException(ErrorCode.TICKET_ACCESS_DENIED, "仅提交人可执行该操作");
        }
    }

    /** 审批记录是否「指派 / 决策」给当前用户（可审 / 已审）。 */
    private static boolean targetsCaller(Approval a, CurrentUser user) {
        if (a.getDecidedBy() != null && a.getDecidedBy() == user.id()) {
            return true;
        }
        if (a.getApproverType() == null || a.getApproverRef() == null) {
            return false;
        }
        return switch (a.getApproverType()) {
            case USER, INSTANCE_OWNER -> a.getApproverRef().equals(String.valueOf(user.id()));
            case ROLE -> user.roleCodes().contains(a.getApproverRef());
        };
    }
}
