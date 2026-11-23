package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.server.auth.RequirePermission;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.RollbackExecutor;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回滚管理端点（对齐 frontend/src/api/index.js 的 listRollbacks / executeRollback /
 * listRollbackStatements）。
 */
@RestController
@RequestMapping("/api/rollbacks")
public class RollbackController {

    private final RollbackDao rollbackDao;
    private final RollbackExecutor rollbackExecutor;

    public RollbackController(RollbackDao rollbackDao, RollbackExecutor rollbackExecutor) {
        this.rollbackDao = rollbackDao;
        this.rollbackExecutor = rollbackExecutor;
    }

    /** 回滚管理：全部回滚主记录（按 id 倒序，最新在前）。 */
    @GetMapping
    public List<RollbackDto> list() {
        return rollbackDao.listAll().stream().map(RollbackDto::from).toList();
    }

    /** 执行回滚（ADMIN/DBA）：把反向语句真正应用到生产库。 */
    @PostMapping("/{id}/execute")
    @RequirePermission(PermissionCode.EXECUTE)
    public RollbackExecutor.Result execute(@PathVariable long id, HttpServletRequest request) {
        long operatorId = currentUserId(request);
        return rollbackExecutor.executeRollback(id, operatorId);
    }

    /** 反向语句明细（展示用，拼字面量 SQL）。 */
    @GetMapping("/{id}/statements")
    public List<StatementDto> statements(@PathVariable long id) {
        return rollbackDao.listStatements(id, 0, -1).stream()
                .map(s -> StatementDto.from(s, rollbackExecutor.renderStatementSql(s)))
                .toList();
    }

    private static long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        return userId == null ? 0L : userId;
    }

    /** 回滚主记录投影：仅暴露前端所需关键字段（generator / status / coverage 按存储码序列化）。 */
    public record RollbackDto(Long id, Long unitId, RollbackGenerator generator, RollbackStatus status,
                              RollbackCoverage coverage, LocalDateTime expiresAt, LocalDateTime createdAt) {
        static RollbackDto from(RollbackSql r) {
            return new RollbackDto(r.getId(), r.getUnitId(), r.getGenerator(), r.getStatus(),
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
