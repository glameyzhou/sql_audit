package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link RollbackDao} 的内存 fake 实现（W0-3，供流 A 单测解耦真实数据库）。
 *
 * <p>语义对齐README §4.4 与接口 javadoc：</p>
 * <ul>
 *   <li>唯一约束以内存判重模拟：rollback_sql.unit_id 唯一、
 *       rollback_statement 的 (rollback_id, stmt_order) 唯一，冲突返回 0；</li>
 *   <li>{@code markStatementApplied} 是条件更新（{@code applied_at IS NULL} 才更新），
 *       逐条幂等——重复回放返回 0 不报错。</li>
 * </ul>
 */
public class InMemoryRollbackDao implements RollbackDao {

    private final Map<Long, RollbackSql> rollbackStore = new ConcurrentHashMap<>();
    private final Map<Long, RollbackStatement> statementStore = new ConcurrentHashMap<>();
    /** unit_id 唯一键索引（uk_unit）。 */
    private final Map<Long, Long> unitIdIndex = new ConcurrentHashMap<>();
    /** (rollback_id, stmt_order) 唯一键索引（uk_rb_order）。 */
    private final Map<String, Long> rbOrderIndex = new ConcurrentHashMap<>();
    private final AtomicLong rollbackIdGenerator = new AtomicLong(0);
    private final AtomicLong statementIdGenerator = new AtomicLong(0);

    @Override
    public synchronized int insertRollbackSql(RollbackSql rollbackSql) {
        if (rollbackSql.getUnitId() == null || unitIdIndex.containsKey(rollbackSql.getUnitId())) {
            return 0; // uk_unit 冲突
        }
        long id = rollbackIdGenerator.incrementAndGet();
        rollbackSql.setId(id);
        rollbackStore.put(id, copy(rollbackSql));
        unitIdIndex.put(rollbackSql.getUnitId(), id);
        return 1;
    }

    @Override
    public synchronized int insertRollbackStatement(RollbackStatement statement) {
        if (statement.getRollbackId() == null || statement.getStmtOrder() == null) {
            return 0;
        }
        String uk = statement.getRollbackId() + ":" + statement.getStmtOrder();
        if (rbOrderIndex.containsKey(uk)) {
            return 0; // uk_rb_order 冲突
        }
        long id = statementIdGenerator.incrementAndGet();
        statement.setId(id);
        statementStore.put(id, copy(statement));
        rbOrderIndex.put(uk, id);
        return 1;
    }

    @Override
    public synchronized List<RollbackStatement> listStatements(long rollbackId, int offset, int limit) {
        List<RollbackStatement> result = new ArrayList<>();
        for (RollbackStatement stmt : statementStore.values()) {
            if (stmt.getRollbackId() != null && stmt.getRollbackId() == rollbackId) {
                result.add(copy(stmt));
            }
        }
        result.sort(Comparator.comparing(RollbackStatement::getStmtOrder));
        int from = Math.min(offset, result.size());
        int to = limit < 0 ? result.size() : Math.min(from + limit, result.size());
        return result.subList(from, to);
    }

    @Override
    public synchronized int markStatementApplied(long statementId, LocalDateTime appliedAt) {
        RollbackStatement stmt = statementStore.get(statementId);
        if (stmt == null) {
            return 0;
        }
        // 条件 UPDATE ... WHERE id=? AND applied_at IS NULL：已 applied 的重复回放返回 0
        if (stmt.getAppliedAt() == null) {
            stmt.setAppliedAt(appliedAt);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized int updateStatus(long id, RollbackStatus status, String unavailableReason) {
        RollbackSql rollbackSql = rollbackStore.get(id);
        if (rollbackSql == null) {
            return 0;
        }
        rollbackSql.setStatus(status);
        rollbackSql.setUnavailableReason(unavailableReason);
        return 1;
    }

    @Override
    public synchronized RollbackSql findByUnitId(long unitId) {
        Long id = unitIdIndex.get(unitId);
        if (id == null) {
            return null;
        }
        RollbackSql rollbackSql = rollbackStore.get(id);
        return rollbackSql == null ? null : copy(rollbackSql);
    }

    @Override
    public synchronized RollbackSql findById(long id) {
        RollbackSql rollbackSql = rollbackStore.get(id);
        return rollbackSql == null ? null : copy(rollbackSql);
    }

    @Override
    public synchronized List<RollbackSql> listAll() {
        List<RollbackSql> result = new ArrayList<>(rollbackStore.values());
        result.sort((a, b) -> Long.compare(b.getId() == null ? 0 : b.getId(), a.getId() == null ? 0 : a.getId()));
        return result;
    }

    @Override
    public synchronized List<RollbackSql> listAll(int offset, int limit) {
        List<RollbackSql> sorted = listAll();
        int from = Math.min(Math.max(offset, 0), sorted.size());
        int to = limit < 0 ? sorted.size() : Math.min(from + limit, sorted.size());
        return new ArrayList<>(sorted.subList(from, to));
    }

    @Override
    public synchronized long countAll() {
        return rollbackStore.size();
    }


    // ---- 内部工具 ----

    private static RollbackSql copy(RollbackSql src) {
        RollbackSql dest = new RollbackSql();
        dest.setId(src.getId());
        dest.setUnitId(src.getUnitId());
        dest.setStmtCount(src.getStmtCount());
        dest.setTotalBytes(src.getTotalBytes());
        dest.setGenerator(src.getGenerator());
        dest.setStatus(src.getStatus());
        dest.setCoverage(src.getCoverage());
        dest.setUnavailableReason(src.getUnavailableReason());
        dest.setPreimageHash(src.getPreimageHash());
        dest.setExpiresAt(src.getExpiresAt());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }

    private static RollbackStatement copy(RollbackStatement src) {
        RollbackStatement dest = new RollbackStatement();
        dest.setId(src.getId());
        dest.setRollbackId(src.getRollbackId());
        dest.setStmtOrder(src.getStmtOrder());
        dest.setReverseSql(src.getReverseSql());
        dest.setValueParams(src.getValueParams());
        dest.setAppliedAt(src.getAppliedAt());
        return dest;
    }
}
