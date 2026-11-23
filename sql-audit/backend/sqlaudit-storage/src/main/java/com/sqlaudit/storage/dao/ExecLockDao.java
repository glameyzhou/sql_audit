package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.ExecLock;

import java.time.LocalDateTime;

/**
 * 执行锁 DAO 接口（README §4.2 执行前门禁 · 并发互斥）。
 *
 * <p>MVP 粒度 = 实例级（{@code inst:{id}}），同实例工单串行；
 * P2 可降到对象级（{@code inst:{id}:obj:{schema.table}}），DDL 必须仍取实例级。</p>
 *
 * <p>W0 只定义接口，不含实现。</p>
 */
public interface ExecLockDao {

    /**
     * 抢锁（原子）：INSERT 捕获主键冲突判影响行数，或 {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * 判影响行数。返回 true 表示抢到。
     */
    boolean tryAcquire(ExecLock lock);

    /** 释放锁：只释放属于自己 unit 的锁（条件 DELETE，防止误删别人的锁）。 */
    boolean release(String lockKey, long unitId);

    /** reaper 清理过期锁（与租约同生命周期）。返回清理条数。 */
    int cleanupExpired(LocalDateTime now);
}
