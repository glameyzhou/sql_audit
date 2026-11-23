package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.dao.ExecLockDao;
import com.sqlaudit.storage.model.ExecLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * exec_lock 的 MySQL 真实现（DDL 执行锁表；内存版语义基准 {@code InMemoryExecLockDao}）。
 *
 * <p><strong>抢锁实现（README §4.2 并发互斥）：</strong>{@code tryAcquire} 用 INSERT 主键冲突法——
 * exec_lock 以 lock_key 为主键，直接 INSERT 并捕获 {@link DuplicateKeyException} 判
 * 「已被占用返回 false」。原子性由主键唯一约束天然保证，无需先查后插的竞态窗口。</p>
 *
 * <p><strong>与内存版的语义差异（推断点，需注意）：</strong>内存版允许对<strong>已过期</strong>
 * 的锁做覆盖抢占；JDBC 版按 DDL 注释「expires_at 过期由 reaper 清理」的分工，
 * 过期锁只能等 {@link #cleanupExpired} 清掉后再抢，tryAcquire 本身不做过期覆盖。
 * 清理→重抢的最终效果与内存版一致，只是恢复窗口多一个 reaper 周期。</p>
 *
 * <p>其余语义与内存版逐条一致：</p>
 * <ul>
 *   <li>{@code release} 条件 DELETE（{@code WHERE lock_key=? AND unit_id=?}），
 *       只释放属于自己 unit 的锁，防误删他人的锁；</li>
 *   <li>{@code cleanupExpired} 清理 {@code expires_at < now} 的锁并返回条数。</li>
 * </ul>
 */
public class MySqlExecLockDao implements ExecLockDao {

    private final JdbcTemplate jdbc;

    public MySqlExecLockDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(ExecLock lock) {
        if (lock == null || lock.getLockKey() == null) {
            return false;
        }
        // DDL：acquired_at / expires_at 均为 NOT NULL——expiresAt 缺失视为调用方契约违约
        if (lock.getExpiresAt() == null) {
            throw new IllegalArgumentException("exec_lock.expires_at NOT NULL（DDL 定义），expiresAt 不能为空");
        }
        try {
            jdbc.update(
                    "INSERT INTO t_exec_lock (lock_key, unit_id, worker_id, acquired_at, expires_at) "
                            + "VALUES (?,?,?,?,?)",
                    lock.getLockKey(), lock.getUnitId(), lock.getWorkerId(),
                    Timestamp.valueOf(lock.getAcquiredAt() != null ? lock.getAcquiredAt() : LocalDateTime.now()),
                    Timestamp.valueOf(lock.getExpiresAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false; // lock_key 主键冲突 = 已被占用（同 owner 也不可剥夺）
        }
    }

    @Override
    public boolean release(String lockKey, long unitId) {
        // 条件 DELETE：unit_id 匹配才删，防止误删别人的锁
        return jdbc.update(
                "DELETE FROM t_exec_lock WHERE lock_key=? AND unit_id=?",
                lockKey, unitId) > 0;
    }

    @Override
    public int cleanupExpired(LocalDateTime now) {
        return jdbc.update("DELETE FROM t_exec_lock WHERE expires_at < ?", Timestamp.valueOf(now));
    }
}
