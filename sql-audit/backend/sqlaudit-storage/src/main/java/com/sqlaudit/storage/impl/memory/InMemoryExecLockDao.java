package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.ExecLockDao;
import com.sqlaudit.storage.model.ExecLock;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExecLockDao} 的内存 fake 实现（W0-3，供流 A 单测解耦真实数据库）。
 *
 * <p>语义对齐设计文档 §3.5 执行前门禁：</p>
 * <ul>
 *   <li>{@code tryAcquire} 原子：lock_key 不存在则插入；已存在且未过期则<strong>不可剥夺</strong>
 *       （owner 相同也一样返回 false）；已过期才允许覆盖抢占；</li>
 *   <li>{@code release} 只删除 lock_key 与 unit_id 都匹配的锁（条件 DELETE，防止误删别人的锁）；</li>
 *   <li>{@code cleanupExpired} 清理过期锁，与租约同生命周期。</li>
 * </ul>
 *
 * <p><strong>推断点：</strong>「当前时间」以入参锁的 {@code acquiredAt} 为基准
 * （未设置时退化为 {@code LocalDateTime.now()}），保证测试确定性——真实 SQL 用 NOW(3)。</p>
 */
public class InMemoryExecLockDao implements ExecLockDao {

    private final Map<String, ExecLock> store = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean tryAcquire(ExecLock lock) {
        if (lock == null || lock.getLockKey() == null) {
            return false;
        }
        ExecLock existing = store.get(lock.getLockKey());
        if (existing == null) {
            store.put(lock.getLockKey(), copy(lock));
            return true;
        }
        LocalDateTime now = lock.getAcquiredAt() != null ? lock.getAcquiredAt() : LocalDateTime.now();
        boolean expired = existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(now);
        if (expired) {
            // 过期覆盖抢占（INSERT ... ON DUPLICATE KEY UPDATE 的等价语义）
            store.put(lock.getLockKey(), copy(lock));
            return true;
        }
        return false; // 未过期不剥夺，同 owner 也不行
    }

    @Override
    public synchronized boolean release(String lockKey, long unitId) {
        ExecLock existing = store.get(lockKey);
        if (existing == null) {
            return false;
        }
        // 条件 DELETE ... WHERE lock_key=? AND unit_id=?
        if (existing.getUnitId() != null && existing.getUnitId() == unitId) {
            store.remove(lockKey);
            return true;
        }
        return false;
    }

    @Override
    public synchronized int cleanupExpired(LocalDateTime now) {
        int cleaned = 0;
        Iterator<Map.Entry<String, ExecLock>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            ExecLock lock = it.next().getValue();
            if (lock.getExpiresAt() != null && lock.getExpiresAt().isBefore(now)) {
                it.remove();
                cleaned++;
            }
        }
        return cleaned;
    }

    // ---- 内部工具 ----

    private static ExecLock copy(ExecLock src) {
        ExecLock dest = new ExecLock();
        dest.setLockKey(src.getLockKey());
        dest.setUnitId(src.getUnitId());
        dest.setWorkerId(src.getWorkerId());
        dest.setAcquiredAt(src.getAcquiredAt());
        dest.setExpiresAt(src.getExpiresAt());
        return dest;
    }
}
