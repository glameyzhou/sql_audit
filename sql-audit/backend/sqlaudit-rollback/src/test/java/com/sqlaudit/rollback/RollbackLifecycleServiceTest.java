package com.sqlaudit.rollback;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.lifecycle.RollbackLifecycleService;
import com.sqlaudit.rollback.reverse.ReverseStatement;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚生命周期测试（§5.9：落库子表 + 语法自检 + 依赖检测 hash + 阈值）。
 */
class RollbackLifecycleServiceTest {

    private final InMemoryRollbackDao dao = new InMemoryRollbackDao();
    private final SqlParserService parser = new SqlParserService();
    private final RollbackLifecycleService service =
            new RollbackLifecycleService(dao, parser);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 18, 0, 0);

    @Test
    void persist_statementsToChildTable_notSingleRow() {
        List<ReverseStatement> statements = List.of(
                new ReverseStatement("UPDATE t SET a = ? WHERE id = ?", List.of(10, 1)),
                new ReverseStatement("UPDATE t SET a = ? WHERE id = ?", List.of(20, 2)));

        long id = service.persistAndVerify(100L, "MIRROR", "FULL",
                List.of("row1", "row2"), statements, Dialect.MYSQL,
                NOW, Duration.ofHours(24));

        // 主记录：verified（语法自检通过）、子表逐条（v2 单行 VARBINARY 错误已修正）
        RollbackSql master = dao.findByUnitId(100L);
        assertNotNull(master);
        assertEquals("verified", master.getStatus());
        assertEquals(2, master.getStmtCount());
        assertEquals(NOW.plusHours(24), master.getExpiresAt());
        assertNotNull(master.getPreimageHash());

        List<RollbackStatement> rows = dao.listStatements(id, 0, 100);
        assertEquals(2, rows.size(), "反向语句落子表，每行一条");
        assertEquals(0, rows.get(0).getStmtOrder());
        assertEquals(1, rows.get(1).getStmtOrder());
    }

    @Test
    void verify_syntaxCheckFailed_marksNotAvailable() {
        // 语法错误语句 → not_available + SYNTAX_CHECK_FAILED
        List<ReverseStatement> bad = List.of(
                new ReverseStatement("UPDATE t SET WHERE ???", List.of()));

        service.persistAndVerify(101L, "MIRROR", "FULL", null, bad, Dialect.MYSQL,
                NOW, Duration.ofHours(24));

        RollbackSql master = dao.findByUnitId(101L);
        assertEquals("not_available", master.getStatus());
        assertTrue(master.getUnavailableReason().startsWith("SYNTAX_CHECK_FAILED"));
    }

    @Test
    void dependencyCheck_hashMismatch_rejected() {
        List<ReverseStatement> stmts = List.of(
                new ReverseStatement("UPDATE t SET a = ? WHERE id = ?", List.of(10, 1)));
        service.persistAndVerify(102L, "MIRROR", "FULL",
                List.of("row1|a=10"), stmts, Dialect.MYSQL, NOW, Duration.ofHours(24));

        // 数据未动：hash 一致 → 可回滚
        assertTrue(service.dependencyCheck(102L, List.of("row1|a=10")));
        // 被后续工单改动：hash 不一致 → 拒绝
        assertEquals(false, service.dependencyCheck(102L, List.of("row1|a=99")));
    }

    @Test
    void dependencyCheck_nullPreimage_passes() {
        // INSERT 反向无前镜像 → 不适用依赖检测，放行
        List<ReverseStatement> stmts = List.of(
                new ReverseStatement("DELETE FROM t WHERE id = ?", List.of(42)));
        service.persistAndVerify(103L, "MIRROR", "FULL", null, stmts,
                Dialect.MYSQL, NOW, Duration.ofHours(24));
        assertTrue(service.dependencyCheck(103L, null));
    }

    @Test
    void threshold_over5000_rejected() {
        List<ReverseStatement> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 5_001; i++) {
            tooMany.add(new ReverseStatement("UPDATE t SET a = ? WHERE id = ?", List.of(i, i)));
        }
        try {
            service.persistAndVerify(104L, "MIRROR", "FULL", null, tooMany,
                    Dialect.MYSQL, NOW, Duration.ofHours(24));
            org.junit.jupiter.api.Assertions.fail("超阈值应拒绝落平台库（转对象存储）");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("对象存储"));
        }
    }

    @Test
    void markStatementApplied_idempotent() {
        List<ReverseStatement> stmts = List.of(
                new ReverseStatement("UPDATE t SET a = ? WHERE id = ?", List.of(10, 1)));
        long id = service.persistAndVerify(105L, "MIRROR", "FULL",
                List.of("r"), stmts, Dialect.MYSQL, NOW, Duration.ofHours(24));

        RollbackStatement row = dao.listStatements(id, 0, 10).get(0);
        assertEquals(1, dao.markStatementApplied(row.getId(), NOW), "首次回放成功");
        assertEquals(0, dao.markStatementApplied(row.getId(), NOW), "二次回放幂等返回 0");
    }
}
