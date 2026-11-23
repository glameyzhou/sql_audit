package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link TicketUnitDao} 的内存 fake 实现（W0-3，供流 A 单测解耦真实数据库）。
 *
 * <p>语义逐条对齐设计文档 §3.3 与接口 javadoc：</p>
 * <ul>
 *   <li>{@code claimUnit} 的「检查 + 写入」在 synchronized 块内原子完成，硬条件
 *       {@code intent_written=0}；</li>
 *   <li>{@code confirmSuccess} 用条件更新（{@code status='executing' AND executed_at IS NULL}）模拟，
 *       不靠唯一约束——影响行数 0 即已被确认，调用方视为成功；</li>
 *   <li>reaper 语义分流：{@code reclaimExpiredLease} 只回收 intent 未预写的，
 *       executing + 心跳停滞走 {@code listExecutingWithStaleHeartbeat}。</li>
 * </ul>
 *
 * <p><strong>与真实 SQL 的差异（推断点）：</strong></p>
 * <ul>
 *   <li>{@code claimUnit} 的租约过期比较（{@code lease_until < NOW(3)}）以参数
 *       {@code heartbeatAt} 作为 NOW（javadoc 约定 heartbeatAt = now），保证测试确定性；</li>
 *   <li>{@code intent_written} 为 null 视为 0（DDL 中 NOT NULL DEFAULT 0）；</li>
 *   <li>查询方法返回快照拷贝，调用方修改返回值不影响内部状态。</li>
 * </ul>
 */
public class InMemoryTicketUnitDao implements TicketUnitDao {

    private final Map<Long, TicketUnit> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    // ---- 变更方法：全部 synchronized，claimUnit 的检查+写入因此天然原子 ----

    @Override
    public synchronized int claimUnit(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        boolean intentNotWritten = !Boolean.TRUE.equals(unit.getIntentWritten());
        boolean statusClaimable = UnitStatus.PENDING == unit.getStatus() || UnitStatus.LEASED == unit.getStatus();
        // lease_until IS NULL OR lease_until < NOW(3)；NOW 以 heartbeatAt 为基准（见类 javadoc）
        boolean leaseFree = unit.getLeaseUntil() == null || unit.getLeaseUntil().isBefore(heartbeatAt);
        if (intentNotWritten && statusClaimable && leaseFree) {
            unit.setLeaseOwner(leaseOwner);
            unit.setLeaseUntil(leaseUntil);
            unit.setHeartbeatAt(heartbeatAt);
            unit.setStatus(UnitStatus.LEASED);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized int writeIntent(long id, LocalDateTime now) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        if (UnitStatus.LEASED == unit.getStatus()) {
            // §3.3 不变式：status='executing' 与 intent_written=1 同事务写入，无中间态
            unit.setStatus(UnitStatus.EXECUTING);
            unit.setIntentWritten(true);
            unit.setHeartbeatAt(now);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized int confirmSuccess(long id, long affectedRows, LocalDateTime executedAt) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        // §3.3 步骤 5：条件 UPDATE，不靠唯一约束；影响行数 0 = 已被确认
        if (UnitStatus.EXECUTING == unit.getStatus() && unit.getExecutedAt() == null) {
            unit.setStatus(UnitStatus.SUCCESS);
            unit.setExecutedAt(executedAt);
            unit.setAffectedRows(affectedRows);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized int confirmFailed(long id, String errorMessage, LocalDateTime executedAt) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        if (UnitStatus.EXECUTING == unit.getStatus()) {
            unit.setStatus(UnitStatus.FAILED);
            unit.setErrorMessage(errorMessage);
            unit.setExecutedAt(executedAt);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized int markNeedsConfirm(long id, String reason) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        unit.setStatus(UnitStatus.NEEDS_CONFIRM);
        unit.setErrorMessage(reason);
        return 1;
    }

    @Override
    public synchronized int updateDdlFingerprint(long id, String fingerprint) {
        // §3.5：审批通过时采集结构指纹写入；执行前 worker 侧重算比对（R_SCHEMA_DRIFT）
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        unit.setDdlFingerprint(fingerprint);
        return 1;
    }

    @Override
    public synchronized int releaseLeaseToPending(long id) {
        TicketUnit unit = store.get(id);
        if (unit == null) {
            return 0;
        }
        clearLease(unit);
        unit.setStatus(UnitStatus.PENDING);
        return 1;
    }

    @Override
    public synchronized int reclaimExpiredLease(LocalDateTime now) {
        int reclaimed = 0;
        for (TicketUnit unit : store.values()) {
            boolean statusRecyclable = UnitStatus.PENDING == unit.getStatus() || UnitStatus.LEASED == unit.getStatus();
            boolean leaseExpired = unit.getLeaseUntil() != null && unit.getLeaseUntil().isBefore(now);
            boolean intentNotWritten = !Boolean.TRUE.equals(unit.getIntentWritten());
            if (statusRecyclable && leaseExpired && intentNotWritten) {
                clearLease(unit);
                unit.setStatus(UnitStatus.PENDING);
                reclaimed++;
            }
        }
        return reclaimed;
    }

    // ---- 查询方法 ----

    @Override
    public synchronized List<TicketUnit> listClaimable(LocalDateTime now, int limit) {
        List<TicketUnit> result = new ArrayList<>();
        for (TicketUnit unit : store.values()) {
            boolean intentNotWritten = !Boolean.TRUE.equals(unit.getIntentWritten());
            boolean pending = UnitStatus.PENDING == unit.getStatus();
            boolean expiredLease = UnitStatus.LEASED == unit.getStatus()
                    && unit.getLeaseUntil() != null && unit.getLeaseUntil().isBefore(now);
            if (intentNotWritten && (pending || expiredLease)) {
                result.add(copy(unit));
            }
            if (result.size() >= limit) {
                break;
            }
        }
        result.sort(Comparator.comparing(TicketUnit::getTicketId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TicketUnit::getUnitOrder, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public synchronized List<TicketUnit> listByTicket(long ticketId) {
        List<TicketUnit> result = new ArrayList<>();
        for (TicketUnit unit : store.values()) {
            if (unit.getTicketId() != null && unit.getTicketId() == ticketId) {
                result.add(copy(unit));
            }
        }
        result.sort(Comparator.comparing(TicketUnit::getUnitOrder, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public synchronized List<TicketUnit> listByStatus(UnitStatus status) {
        List<TicketUnit> result = new ArrayList<>();
        for (TicketUnit unit : store.values()) {
            if (status == unit.getStatus()) {
                result.add(copy(unit));
            }
        }
        result.sort(Comparator.comparing(TicketUnit::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public synchronized int renewLease(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt) {
        TicketUnit unit = store.get(id);
        if (unit == null || UnitStatus.EXECUTING != unit.getStatus()) {
            return 0;
        }
        if (leaseOwner != null && !leaseOwner.equals(unit.getLeaseOwner())) {
            return 0;
        }
        unit.setLeaseUntil(leaseUntil);
        unit.setHeartbeatAt(heartbeatAt);
        return 1;
    }

    @Override
    public synchronized TicketUnit findById(long id) {
        TicketUnit unit = store.get(id);
        return unit == null ? null : copy(unit);
    }

    @Override
    public synchronized List<TicketUnit> listExecutingWithStaleHeartbeat(LocalDateTime heartbeatThreshold) {
        List<TicketUnit> result = new ArrayList<>();
        for (TicketUnit unit : store.values()) {
            boolean executing = UnitStatus.EXECUTING == unit.getStatus();
            boolean stale = unit.getHeartbeatAt() != null && unit.getHeartbeatAt().isBefore(heartbeatThreshold);
            boolean unconfirmed = unit.getExecutedAt() == null;
            if (executing && stale && unconfirmed) {
                result.add(copy(unit));
            }
        }
        return result;
    }

    @Override
    public synchronized List<TicketUnit> listByFingerprintSince(String execFingerprint, LocalDateTime since) {
        List<TicketUnit> result = new ArrayList<>();
        for (TicketUnit unit : store.values()) {
            boolean fingerprintMatch = execFingerprint != null && execFingerprint.equals(unit.getExecFingerprint());
            boolean inWindow = unit.getCreatedAt() != null && !unit.getCreatedAt().isBefore(since);
            if (fingerprintMatch && inWindow) {
                result.add(copy(unit));
            }
        }
        result.sort(Comparator.comparing(TicketUnit::getCreatedAt));
        return result;
    }

    // ---- 测试辅助（不属于接口，流 A 单测用来造数与断言） ----

    /** 插入一条 unit（自增 id），返回分配的 id。 */
    @Override
    public synchronized long insert(TicketUnit unit) {
        long id = idGenerator.incrementAndGet();
        unit.setId(id);
        store.put(id, copy(unit));
        return id;
    }

    /** 按状态计数（流 A 断言用）。 */
    public synchronized int countByStatus(UnitStatus status) {
        int count = 0;
        for (TicketUnit unit : store.values()) {
            if (status == unit.getStatus()) {
                count++;
            }
        }
        return count;
    }

    // ---- 内部工具 ----

    private void clearLease(TicketUnit unit) {
        unit.setLeaseOwner(null);
        unit.setLeaseUntil(null);
        unit.setHeartbeatAt(null);
    }

    /** 深拷贝：返回给调用方的是快照，防止绕过条件更新直接改内存状态。 */
    private static TicketUnit copy(TicketUnit src) {
        TicketUnit dest = new TicketUnit();
        dest.setId(src.getId());
        dest.setTicketId(src.getTicketId());
        dest.setInstanceId(src.getInstanceId());
        dest.setSchemaName(src.getSchemaName());
        dest.setObjectName(src.getObjectName());
        dest.setUnitOrder(src.getUnitOrder());
        dest.setStatement(src.getStatement());
        dest.setStatementHash(src.getStatementHash());
        dest.setStatementType(src.getStatementType());
        dest.setDialectSpecific(src.getDialectSpecific());
        dest.setStatus(src.getStatus());
        dest.setLeaseOwner(src.getLeaseOwner());
        dest.setLeaseUntil(src.getLeaseUntil());
        dest.setHeartbeatAt(src.getHeartbeatAt());
        dest.setIntentWritten(src.getIntentWritten());
        dest.setExecutedAt(src.getExecutedAt());
        dest.setExecFingerprint(src.getExecFingerprint());
        dest.setPreimageRows(src.getPreimageRows());
        dest.setAffectedRows(src.getAffectedRows());
        dest.setDdlFingerprint(src.getDdlFingerprint());
        dest.setBinlogStart(src.getBinlogStart());
        dest.setBinlogEnd(src.getBinlogEnd());
        dest.setErrorMessage(src.getErrorMessage());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
