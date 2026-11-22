package com.sqlaudit.rollback;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.preimage.PreimagePlanner;
import com.sqlaudit.rule.TableMetadataProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前镜像构造测试（§5.3 / §5.4：列集合规则 + WHERE 复用 + FOR UPDATE + 生成列排除）。
 */
class PreimagePlannerTest {

    private final SqlParserService parser = new SqlParserService();
    private final PreimagePlanner planner = new PreimagePlanner();

    /** t：主键 id，生成列 gen_col（VIRTUAL），普通列 a/b。 */
    private static final TableMetadataProvider META = new TableMetadataProvider() {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public TableMetadata getTable(String schema, String name) {
            return new TableMetadata(true, List.of("id"),
                    Set.of("gen_col"), List.of("id", "a", "b", "gen_col"), Set.of(), true);
        }
    };

    private ParsedStatement parse(String sql) {
        return parser.parse(sql, Dialect.MYSQL).get(0);
    }

    @Test
    void updatePreimage_pkPlusSetColumns_forUpdate() {
        ParsedStatement ps = parse("UPDATE t SET a = 1, b = 'x' WHERE id IN (1, 2, 3)");
        PreimagePlanner.PreimageQuery q = planner.plan(ps, META);

        // 列序：主键在前 + SET 列（§5.4 示例：SELECT pk,a,b FROM t WHERE <原 WHERE> FOR UPDATE）
        assertEquals(List.of("id", "a", "b"), q.columns());
        String flat = q.sqlText().toLowerCase().replaceAll("\\s+", " ");
        assertTrue(flat.contains("for update"), "前镜像必须 FOR UPDATE（与 DML 同事务锁行）");
        assertTrue(flat.contains("where id in (1, 2, 3)"), "WHERE 与原语句完全一致");
        assertTrue(flat.startsWith("select id, a, b from t"), "列清单 = 主键 + SET 列，非 SELECT *");
    }

    @Test
    void updatePreimage_setColumnEqualsPk_noDuplicate() {
        // SET id = 99（改主键）：主键列已在列首，不重复
        ParsedStatement ps = parse("UPDATE t SET id = 99, a = 1 WHERE id = 5");
        PreimagePlanner.PreimageQuery q = planner.plan(ps, META);
        assertEquals(List.of("id", "a"), q.columns());
    }

    @Test
    void deletePreimage_allNonGeneratedColumns() {
        ParsedStatement ps = parse("DELETE FROM t WHERE a = 1");
        PreimagePlanner.PreimageQuery q = planner.planDelete(
                (com.alibaba.druid.sql.ast.statement.SQLDeleteStatement) ps.getSourceStatement(),
                META, Dialect.MYSQL);

        // 生成列必须从列清单排除（§5.3：反向 INSERT 报 generated column 错误）
        assertEquals(List.of("id", "a", "b"), q.columns());
        assertFalse(q.columns().contains("gen_col"));
        assertTrue(q.sqlText().toLowerCase().contains("for update"));
    }

    @Test
    void updatePreimage_pgDialect() {
        ParsedStatement ps = parser.parse("UPDATE t SET a = 1 WHERE id = 1", Dialect.PG).get(0);
        PreimagePlanner.PreimageQuery q = planner.plan(ps, META);
        assertTrue(q.sqlText().toLowerCase().contains("for update"));
        assertEquals(List.of("id", "a"), q.columns());
    }
}
