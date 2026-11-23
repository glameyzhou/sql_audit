package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryRollbackDao} 单测：唯一键判重、分页按序读取、逐条幂等回放。
 */
class InMemoryRollbackDaoTest {

    private final InMemoryRollbackDao dao = new InMemoryRollbackDao();

    private long insertRollback(long unitId) {
        RollbackSql rollbackSql = new RollbackSql();
        rollbackSql.setUnitId(unitId);
        rollbackSql.setStatus(RollbackStatus.GENERATED);
        assertEquals(1, dao.insertRollbackSql(rollbackSql));
        return rollbackSql.getId();
    }

    @Test
    void insertRollbackSql_unitId唯一_重复插入返回0() {
        insertRollback(100L);
        RollbackSql duplicate = new RollbackSql();
        duplicate.setUnitId(100L);
        duplicate.setStatus(RollbackStatus.GENERATED);
        assertEquals(0, dao.insertRollbackSql(duplicate), "uk_unit 冲突应返回 0");

        assertNotNull(dao.findByUnitId(100L));
        assertNull(dao.findByUnitId(999L));
    }

    @Test
    void insertRollbackStatement_rollbackId加stmtOrder唯一() {
        long rollbackId = insertRollback(100L);

        RollbackStatement first = makeStatement(rollbackId, 1);
        assertEquals(1, dao.insertRollbackStatement(first));

        RollbackStatement duplicateOrder = makeStatement(rollbackId, 1);
        assertEquals(0, dao.insertRollbackStatement(duplicateOrder), "uk_rb_order 冲突应返回 0");

        RollbackStatement second = makeStatement(rollbackId, 2);
        assertEquals(1, dao.insertRollbackStatement(second));
    }

    @Test
    void listStatements_按stmtOrder升序_分页正确() {
        long rollbackId = insertRollback(100L);
        // 乱序插入 3 条
        dao.insertRollbackStatement(makeStatement(rollbackId, 3));
        dao.insertRollbackStatement(makeStatement(rollbackId, 1));
        dao.insertRollbackStatement(makeStatement(rollbackId, 2));

        List<RollbackStatement> all = dao.listStatements(rollbackId, 0, 10);
        assertEquals(3, all.size());
        assertEquals(1, all.get(0).getStmtOrder());
        assertEquals(2, all.get(1).getStmtOrder());
        assertEquals(3, all.get(2).getStmtOrder());

        // 分批流式读取：offset + limit
        List<RollbackStatement> batch = dao.listStatements(rollbackId, 1, 1);
        assertEquals(1, batch.size());
        assertEquals(2, batch.get(0).getStmtOrder());

        // 其他 rollback 的语句不混入
        long otherId = insertRollback(200L);
        dao.insertRollbackStatement(makeStatement(otherId, 1));
        assertEquals(3, dao.listStatements(rollbackId, 0, 10).size());
    }

    @Test
    void markStatementApplied_条件更新幂等() {
        long rollbackId = insertRollback(100L);
        RollbackStatement stmt = makeStatement(rollbackId, 1);
        dao.insertRollbackStatement(stmt);
        long statementId = stmt.getId();

        LocalDateTime appliedAt = LocalDateTime.now();
        assertEquals(1, dao.markStatementApplied(statementId, appliedAt));
        assertEquals(appliedAt, dao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt());

        // 已 applied 的重复回放返回 0（applied_at IS NULL 条件不满足）
        assertEquals(0, dao.markStatementApplied(statementId, appliedAt.plusSeconds(1)));
        assertEquals(appliedAt, dao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt(),
                "重复回放不得覆盖首次回放时间戳");

        // 不存在的语句返回 0
        assertEquals(0, dao.markStatementApplied(99999L, appliedAt));
    }

    @Test
    void updateStatus_更新主记录状态与原因() {
        long rollbackId = insertRollback(100L);
        assertEquals(1, dao.updateStatus(rollbackId, RollbackStatus.NOT_AVAILABLE, "R_ROLLBACK_UNAVAILABLE"));
        RollbackSql rollbackSql = dao.findByUnitId(100L);
        assertEquals(RollbackStatus.NOT_AVAILABLE, rollbackSql.getStatus());
        assertEquals("R_ROLLBACK_UNAVAILABLE", rollbackSql.getUnavailableReason());

        assertEquals(0, dao.updateStatus(99999L, RollbackStatus.VERIFIED, null));
    }

    @Test
    void listAll分页_按id倒序_计数() {
        long r1 = insertRollback(100L);
        long r2 = insertRollback(200L);
        insertRollback(300L);
        insertRollback(400L);

        assertEquals(4L, dao.countAll());
        List<RollbackSql> page1 = dao.listAll(0, 3);
        assertEquals(3, page1.size());
        assertTrue(page1.get(0).getId() > page1.get(1).getId(), "id 倒序（最新在前）");
        List<RollbackSql> page2 = dao.listAll(3, 3);
        assertEquals(1, page2.size());
        assertEquals(r1, page2.get(0).getId(), "最早一条在尾页");
        assertTrue(dao.listAll(99, 3).isEmpty(), "越界页为空");
        assertEquals(4, dao.listAll(0, -1).size(), "limit<0 取全部");
        assertTrue(r2 > r1, "自增 id 递增（前提断言）");
    }

    private RollbackStatement makeStatement(long rollbackId, int stmtOrder) {
        RollbackStatement statement = new RollbackStatement();
        statement.setRollbackId(rollbackId);
        statement.setStmtOrder(stmtOrder);
        statement.setReverseSql("UPDATE t_demo SET a=10 WHERE id=1");
        statement.setValueParams("{\"a\":10}");
        return statement;
    }
}
