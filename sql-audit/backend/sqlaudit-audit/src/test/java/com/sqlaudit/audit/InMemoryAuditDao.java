package com.sqlaudit.audit;

import com.sqlaudit.common.audit.CanonicalJson;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版 AuditDao，测试专用（模拟 audit_log + chain_head 两张表）。
 *
 * <p>用 {@link ReentrantLock} 模拟 {@code SELECT ... FOR UPDATE} 行锁：
 * {@link #lockChainHead()} 加锁，{@link #updateChainHead(long, String)} 解锁
 * （对应真实 DB 中锁存活到事务 commit）。这样 100 线程并发 append 时，
 * 每个线程从 lockChainHead 到 updateChainHead 的写入流程被串行化，
 * 与生产单事务行为一致。</p>
 */
class InMemoryAuditDao implements AuditDao {

    /** 模拟 chain_head 行锁（FOR UPDATE） */
    private final ReentrantLock chainLock = new ReentrantLock();

    /** seq → 记录（TreeMap 按 seq 有序） */
    private final Map<Long, AuditLog> table = new TreeMap<>();

    /** 模拟 AUTO_INCREMENT 物理主键 */
    private final AtomicLong idGen = new AtomicLong();

    private long lastSeq = 0;
    private String lastHash = CanonicalJson.GENESIS_HASH; // 模拟创世 chain_head 初值（README §4.1）

    @Override
    public ChainHead lockChainHead() {
        chainLock.lock(); // 模拟 FOR UPDATE：持有到 updateChainHead（事务提交）
        ChainHead head = new ChainHead();
        head.setId(1);
        head.setLastSeq(lastSeq);
        head.setLastHash(lastHash);
        head.setUpdatedAt(LocalDateTime.now());
        return head;
    }

    @Override
    public int insert(AuditLog log) {
        if (log.getSeq() == null || table.containsKey(log.getSeq())) {
            chainLock.unlock(); // 模拟事务失败回滚释放锁
            throw new IllegalStateException("seq 重复或为空: " + log.getSeq());
        }
        log.setId(idGen.incrementAndGet());
        table.put(log.getSeq(), log);
        return 1;
    }

    @Override
    public int updateChainHead(long newLastSeq, String newLastHash) {
        this.lastSeq = newLastSeq;
        this.lastHash = newLastHash;
        chainLock.unlock(); // 模拟事务提交、行锁释放
        return 1;
    }

    @Override
    public List<AuditLog> listBySeqRange(long fromSeq, long toSeq) {
        List<AuditLog> result = new ArrayList<>();
        for (Map.Entry<Long, AuditLog> e : table.entrySet()) {
            long seq = e.getKey();
            if (seq >= fromSeq && seq <= toSeq) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    @Override
    public List<AuditLog> listBySeqRange(long fromSeq, long toSeq, int offset, int limit) {
        List<AuditLog> sorted = listBySeqRange(fromSeq, toSeq);
        int from = Math.min(Math.max(offset, 0), sorted.size());
        int to = limit < 0 ? sorted.size() : Math.min(from + limit, sorted.size());
        return new ArrayList<>(sorted.subList(from, to));
    }

    @Override
    public long countBySeqRange(long fromSeq, long toSeq) {
        return listBySeqRange(fromSeq, toSeq).size();
    }

    /** 直接读某条（测试篡改用）。 */
    AuditLog get(long seq) {
        return table.get(seq);
    }

    /** 模拟违规 DELETE（append-only 被破坏时链应断裂，测试篡改用）。 */
    void delete(long seq) {
        table.remove(seq);
    }

    /** 当前条数。 */
    int size() {
        return table.size();
    }
}
