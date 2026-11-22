package com.sqlaudit.server.web;

import com.sqlaudit.audit.ChainVerifier;
import com.sqlaudit.audit.ChainVerifyResult;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计端点（§7：检索 + 链完整性校验）。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final AuditDao auditDao;
    private final ChainVerifier chainVerifier;

    public AuditController(AuditDao auditDao, ChainVerifier chainVerifier) {
        this.auditDao = auditDao;
        this.chainVerifier = chainVerifier;
    }

    /** 按 seq 区间检索（W2 补 actor/action 过滤与分页）。 */
    @GetMapping
    public List<AuditLog> list(@RequestParam(defaultValue = "1") long fromSeq,
                               @RequestParam(defaultValue = "100") long toSeq) {
        return auditDao.listBySeqRange(fromSeq, toSeq);
    }

    /** 链完整性校验（篡改检测入口，§7.3）。 */
    @GetMapping("/verify")
    public ChainVerifyResult verify(@RequestParam(defaultValue = "1") long fromSeq,
                                    @RequestParam(defaultValue = "1000000") long toSeq) {
        return chainVerifier.verify(fromSeq, toSeq);
    }
}
