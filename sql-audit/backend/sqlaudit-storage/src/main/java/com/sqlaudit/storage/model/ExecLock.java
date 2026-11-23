package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 执行锁实体（对应 exec_lock 表，README §4.2 并发互斥）。
 * <p>锁与租约同生命周期，过期由 reaper 清理。MVP 粒度 = 实例级。</p>
 * <p>唯一写入方：sqlaudit-executor。</p>
 */
public class ExecLock {

    /** inst:{id} 或 inst:{id}:obj:{schema.table} */
    private String lockKey;
    private Long unitId;
    private String workerId;
    private LocalDateTime acquiredAt;
    private LocalDateTime expiresAt;

    public String getLockKey() {
        return lockKey;
    }

    public void setLockKey(String lockKey) {
        this.lockKey = lockKey;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public LocalDateTime getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(LocalDateTime acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
