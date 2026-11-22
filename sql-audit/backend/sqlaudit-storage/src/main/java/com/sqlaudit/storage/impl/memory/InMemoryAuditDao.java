package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link AuditDao} 的内存 fake 实现（W0-3，供流 A 单测解耦真实数据库）。
 *
 * <p>hash 链写入流程（接口 javadoc）：
 * {@code lockChainHead()} → 取 last_seq/last_hash → 算本条 hash →
 * {@code insert()} → {@code updateChainHead()}。</p>
 *
 * <p><strong>锁模型简化（与真实语义的差异）：</strong>真实实现是
 * {@code SELECT ... FOR UPDATE} 的行锁，事务提交自动释放；fake 用
 * {@link ReentrantLock} 模拟——{@code lockChainHead()} 获取锁并返回链头快照，
 * {@code updateChainHead()} 更新链头并释放锁。因此：</p>
 * <ul>
 *   <li>单条 append 流程（lock → insert → updateChainHead）内天然串行，seq 连续无空洞；</li>
 *   <li>局限 A：调用方必须成对调用 lock/updateChainHead，否则锁泄漏（真实实现由事务边界保证，
 *       fake 无法感知事务）；</li>
 *   <li>局限 B：并发第二个 {@code lockChainHead()} 会阻塞直至第一个流程完成
 *       ——与 FOR UPDATE 的等待语义一致。</li>
 * </ul>
 *
 * <p>链头初始值（§7.3 创世约定）：{@code last_seq=0}、{@code last_hash=64 个 '0'}。</p>
 */
public class InMemoryAuditDao implements AuditDao {

    /** 创世链头：last_hash = SHA256 空前缀约定，64 个 '0'。 */
    private static final String GENESIS_HASH = "0".repeat(64);

    private final Map<Long, AuditLog> logStore = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);
    /** 模拟 chain_head 行锁（SELECT ... WHERE id=1 FOR UPDATE）。 */
    private final ReentrantLock chainLock = new ReentrantLock();
    private ChainHead chainHead = initChainHead();

    @Override
    public ChainHead lockChainHead() {
        chainLock.lock(); // 阻塞直至前一个 append 流程完成，模拟 FOR UPDATE 等待
        synchronized (this) {
            return copyChainHead(chainHead);
        }
    }

    @Override
    public synchronized int insert(AuditLog log) {
        long id = idGenerator.incrementAndGet();
        log.setId(id);
        logStore.put(id, copy(log));
        return 1;
    }

    @Override
    public synchronized int updateChainHead(long lastSeq, String lastHash) {
        chainHead.setLastSeq(lastSeq);
        chainHead.setLastHash(lastHash);
        chainHead.setUpdatedAt(LocalDateTime.now());
        // 模拟事务提交释放行锁；未持锁时（流程外调用）只更新不释放
        if (chainLock.isHeldByCurrentThread()) {
            chainLock.unlock();
        }
        return 1;
    }

    @Override
    public synchronized List<AuditLog> listBySeqRange(long fromSeq, long toSeq) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logStore.values()) {
            if (log.getSeq() != null && log.getSeq() >= fromSeq && log.getSeq() <= toSeq) {
                result.add(copy(log));
            }
        }
        result.sort(Comparator.comparing(AuditLog::getSeq));
        return result;
    }

    // ---- 测试辅助（不属于接口） ----

    /** 不加锁直接读链头快照（流 A 断言用，勿在 append 流程外用它替代 lockChainHead）。 */
    public synchronized ChainHead peekChainHead() {
        return copyChainHead(chainHead);
    }

    /** 强制释放链锁（测试兜底：lockChainHead 后未走 updateChainHead 时防泄漏）。 */
    public void forceUnlockChain() {
        if (chainLock.isHeldByCurrentThread()) {
            chainLock.unlock();
        }
    }

    // ---- 内部工具 ----

    private static ChainHead initChainHead() {
        ChainHead head = new ChainHead();
        head.setId(1); // 单行表恒 id=1
        head.setLastSeq(0L);
        head.setLastHash(GENESIS_HASH);
        head.setUpdatedAt(null);
        return head;
    }

    private static ChainHead copyChainHead(ChainHead src) {
        ChainHead dest = new ChainHead();
        dest.setId(src.getId());
        dest.setLastSeq(src.getLastSeq());
        dest.setLastHash(src.getLastHash());
        dest.setUpdatedAt(src.getUpdatedAt());
        return dest;
    }

    private static AuditLog copy(AuditLog src) {
        AuditLog dest = new AuditLog();
        dest.setId(src.getId());
        dest.setSeq(src.getSeq());
        dest.setActorId(src.getActorId());
        dest.setActorName(src.getActorName());
        dest.setActorRole(src.getActorRole());
        dest.setAction(src.getAction());
        dest.setTargetType(src.getTargetType());
        dest.setTargetId(src.getTargetId());
        dest.setInstanceId(src.getInstanceId());
        dest.setDetail(src.getDetail());
        dest.setResult(src.getResult());
        dest.setClientIp(src.getClientIp());
        dest.setUserAgent(src.getUserAgent());
        dest.setTraceId(src.getTraceId());
        dest.setPrevHash(src.getPrevHash());
        dest.setHash(src.getHash());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
