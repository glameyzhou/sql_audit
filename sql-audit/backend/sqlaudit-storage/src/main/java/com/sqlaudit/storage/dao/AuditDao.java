package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;

import java.util.List;

/**
 * 审计 DAO 接口（README §4.1 不可篡改设计 · hash 链）。
 *
 * <p>写入流程（单事务，由 sqlaudit-audit service 控制事务边界）：
 * {@code lockChainHead()} → 取 last_seq/last_hash → 算本条 hash → {@code insert()} →
 * {@code updateChainHead()}。行锁天然串行化，seq 连续无空洞。</p>
 *
 * <p>W0 只定义接口，不含实现。注意 {@code lockChainHead} 必须在事务内调用才有意义。</p>
 */
public interface AuditDao {

    /** 锁定并读取链头（{@code SELECT ... WHERE id=1 FOR UPDATE}）。必须在事务内调用。 */
    ChainHead lockChainHead();

    /** 追加一条审计日志（append-only；应用账号只有 INSERT/SELECT 权限）。 */
    int insert(AuditLog log);

    /** 更新链头（last_seq / last_hash）。 */
    int updateChainHead(long lastSeq, String lastHash);

    /** 按 seq 区间读取，用于链完整性校验。 */
    List<AuditLog> listBySeqRange(long fromSeq, long toSeq);

    /** 按 seq 区间分页读取（服务端分页，seq 升序）。{@code limit < 0} 视为取全部。 */
    List<AuditLog> listBySeqRange(long fromSeq, long toSeq, int offset, int limit);

    /** 按 seq 区间计数。 */
    long countBySeqRange(long fromSeq, long toSeq);
}
