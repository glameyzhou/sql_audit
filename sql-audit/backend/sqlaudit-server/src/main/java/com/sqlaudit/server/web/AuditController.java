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
 * 审计端点（README §4.1：检索 + 链完整性校验）。
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

    /**
     * 按 seq 区间检索（seq 升序；{@code fromSeq}/{@code toSeq} 默认保持 1/100）。
     * 分页 {@code page}/{@code size}（钳制见 {@link PagedResponse}），返回 {@code {items, total}}。
     */
    @GetMapping
    public PagedResponse<AuditLog> list(@RequestParam(defaultValue = "1") long fromSeq,
                                        @RequestParam(defaultValue = "100") long toSeq,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = PagedResponse.DEFAULT_SIZE_STR) int size) {
        int limit = PagedResponse.clampSize(size);
        int offset = PagedResponse.offsetOf(page, limit);
        List<AuditLog> items = auditDao.listBySeqRange(fromSeq, toSeq, offset, limit);
        long total = auditDao.countBySeqRange(fromSeq, toSeq);
        return PagedResponse.of(items, total);
    }

    /** 链完整性校验（篡改检测入口，README §4.1）。 */
    @GetMapping("/verify")
    public ChainVerifyResult verify(@RequestParam(defaultValue = "1") long fromSeq,
                                    @RequestParam(defaultValue = "1000000") long toSeq) {
        return chainVerifier.verify(fromSeq, toSeq);
    }
}
