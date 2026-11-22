package com.sqlaudit.server.web;

import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回滚管理端点（对齐 frontend/src/api/index.js 的 listRollbacks）。
 */
@RestController
@RequestMapping("/api/rollbacks")
public class RollbackController {

    private final RollbackDao rollbackDao;

    public RollbackController(RollbackDao rollbackDao) {
        this.rollbackDao = rollbackDao;
    }

    /** 回滚管理：全部回滚主记录（按 id 倒序，最新在前）。 */
    @GetMapping
    public List<RollbackDto> list() {
        return rollbackDao.listAll().stream().map(RollbackDto::from).toList();
    }

    /** 回滚主记录投影：仅暴露前端所需关键字段。 */
    public record RollbackDto(Long id, Long unitId, String generator, String status,
                              String coverage, LocalDateTime expiresAt, LocalDateTime createdAt) {
        static RollbackDto from(RollbackSql r) {
            return new RollbackDto(r.getId(), r.getUnitId(), r.getGenerator(), r.getStatus(),
                    r.getCoverage(), r.getExpiresAt(), r.getCreatedAt());
        }
    }
}
