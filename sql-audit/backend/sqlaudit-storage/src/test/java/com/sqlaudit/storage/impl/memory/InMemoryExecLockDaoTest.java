package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.model.ExecLock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryExecLockDao} 单测：未过期不可剥夺、过期可抢、release 只删自己的锁。
 */
class InMemoryExecLockDaoTest {

    private final InMemoryExecLockDao dao = new InMemoryExecLockDao();

    private ExecLock makeLock(String lockKey, long unitId, String workerId, LocalDateTime acquiredAt, LocalDateTime expiresAt) {
        ExecLock lock = new ExecLock();
        lock.setLockKey(lockKey);
        lock.setUnitId(unitId);
        lock.setWorkerId(workerId);
        lock.setAcquiredAt(acquiredAt);
        lock.setExpiresAt(expiresAt);
        return lock;
    }

    @Test
    void tryAcquire_不存在则插入成功() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // 同 key 第二个 unit（不同 owner）：未过期不可剥夺
        assertFalse(dao.tryAcquire(makeLock("inst:1", 11L, "worker-b", now, now.plusSeconds(30))));
    }

    @Test
    void tryAcquire_未过期不可剥夺_同owner也一样() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // 同 owner 重入也算剥夺，返回 false
        assertFalse(dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.plusSeconds(1), now.plusSeconds(31))));
    }

    @Test
    void tryAcquire_过期可被抢占覆盖() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.minusSeconds(60), now.minusSeconds(30))));

        // 已过期：worker-b 覆盖抢占成功，锁归属转移
        assertTrue(dao.tryAcquire(makeLock("inst:1", 11L, "worker-b", now, now.plusSeconds(30))));

        // 原 owner（unit 10）release 不掉新锁
        assertFalse(dao.release("inst:1", 10L));
    }

    @Test
    void release_只删lockKey与unitId都匹配的锁() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // unitId 不匹配：别人的锁删不掉
        assertFalse(dao.release("inst:1", 999L));
        // lockKey 不匹配
        assertFalse(dao.release("inst:2", 10L));

        // 匹配：删除成功
        assertTrue(dao.release("inst:1", 10L));
        // 已删除：再删返回 false
        assertFalse(dao.release("inst:1", 10L));

        // 删除后可重新抢到
        assertTrue(dao.tryAcquire(makeLock("inst:1", 12L, "worker-c", now, now.plusSeconds(30))));
    }

    @Test
    void cleanupExpired_只清过期的并返回条数() {
        LocalDateTime now = LocalDateTime.now();
        dao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.minusSeconds(60), now.minusSeconds(30)));
        dao.tryAcquire(makeLock("inst:2", 20L, "worker-b", now.minusSeconds(60), now.minusSeconds(30)));
        dao.tryAcquire(makeLock("inst:3", 30L, "worker-c", now, now.plusSeconds(30)));

        assertEquals(2, dao.cleanupExpired(now));

        // 未过期的还在：unit 30 仍持有 inst:3
        assertFalse(dao.tryAcquire(makeLock("inst:3", 31L, "worker-d", now, now.plusSeconds(30))));
        // 过期的已被清走：inst:1 可重新抢到
        assertTrue(dao.tryAcquire(makeLock("inst:1", 11L, "worker-e", now, now.plusSeconds(30))));
    }
}
