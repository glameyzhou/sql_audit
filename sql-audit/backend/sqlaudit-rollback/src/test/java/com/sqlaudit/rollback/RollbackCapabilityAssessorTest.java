package com.sqlaudit.rollback;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnavailableReason;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RollbackPlan;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.capability.CapabilityContext;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.TableMetadataProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 回滚能力评估测试（A-3 验收：§5.2 覆盖率矩阵逐格）。
 */
class RollbackCapabilityAssessorTest {

    private final SqlParserService parser = new SqlParserService();
    private final RollbackCapabilityAssessor assessor = new RollbackCapabilityAssessor();

    private static final class Meta implements TableMetadataProvider {
        final Map<String, TableMetadata> tables = new HashMap<>();

        void withTable(String name, String... pkCols) {
            tables.put(name, new TableMetadata(pkCols.length > 0, List.of(pkCols),
                    Set.of(), allCols(pkCols), Set.of(), true));
        }

        private List<String> allCols(String[] pk) {
            // 常规列集：主键 + 若干普通列
            return pk.length == 0 ? List.of("a", "b", "phone") : List.of(pk[0], "a", "b", "phone");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public TableMetadata getTable(String schema, String name) {
            return tables.get(name);
        }
    }

    private RollbackPlan assessMySql(String sql, Meta meta, RuleHit... hits) {
        List<ParsedStatement> units = parser.parse(sql, Dialect.MYSQL);
        return assessor.assess(new CapabilityContext(units.get(0), List.of(hits), meta));
    }

    private RollbackPlan assessPg(String sql, Meta meta, RuleHit... hits) {
        List<ParsedStatement> units = parser.parse(sql, Dialect.PG);
        return assessor.assess(new CapabilityContext(units.get(0), List.of(hits), meta));
    }

    private static RuleHit hit(RuleCode code) {
        return new RuleHit(code, com.sqlaudit.common.enums.RuleSeverity.ERROR, "test");
    }

    // ---------- §5.2 第 1 行：UPDATE + 主键 + 确定性 SET ----------

    @Test
    void matrix_updateDeterministic_full() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql("UPDATE t SET a = 1, b = 'x' WHERE id = 1", meta);
        assertEquals(RollbackCoverage.FULL, plan.getCoverage());
        assertEquals(RollbackGenerator.MIRROR, plan.getGenerator());
        assertNull(plan.getUnavailableReason());

        RollbackPlan pg = assessPg("UPDATE t SET a = 1 WHERE id = 1", meta);
        assertEquals(RollbackCoverage.FULL, pg.getCoverage());
    }

    // ---------- §5.2 第 2 行：DELETE ----------

    @Test
    void matrix_delete_full() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql("DELETE FROM t WHERE id = 1", meta);
        assertEquals(RollbackCoverage.FULL, plan.getCoverage());
        assertEquals(RollbackGenerator.MIRROR, plan.getGenerator());
    }

    // ---------- §5.2 第 3 行：INSERT 单条 ----------

    @Test
    void matrix_insertSingleExplicitPk_full() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql("INSERT INTO t (id, a, b) VALUES (1, 2, 'x')", meta);
        assertEquals(RollbackCoverage.FULL, plan.getCoverage());
        assertEquals(RollbackGenerator.MIRROR, plan.getGenerator());
    }

    @Test
    void matrix_insertSingleAutoIncrement_full_dialectNotes() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        // MySQL：LAST_INSERT_ID 路径
        RollbackPlan mysql = assessMySql("INSERT INTO t (a, b) VALUES (1, 'x')", meta);
        assertEquals(RollbackCoverage.FULL, mysql.getCoverage());
        // PG：RETURNING 路径
        RollbackPlan pg = assessPg("INSERT INTO t (a, b) VALUES (1, 'x')", meta);
        assertEquals(RollbackCoverage.FULL, pg.getCoverage());
    }

    // ---------- §5.2 第 4 行：批量 / INSERT...SELECT / ON DUPLICATE / REPLACE ----------

    @Test
    void matrix_batchInsert_mysqlNone_pgPartial() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        // MySQL 批量：NONE + BATCH_INSERT
        RollbackPlan mysql = assessMySql(
                "INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (3, 'z')", meta);
        assertEquals(RollbackCoverage.NONE, mysql.getCoverage());
        assertEquals(UnavailableReason.BATCH_INSERT, mysql.getUnavailableReason());
        // PG 批量：PARTIAL（RETURNING 多行可覆盖，S2 待验证）
        RollbackPlan pg = assessPg("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y')", meta);
        assertEquals(RollbackCoverage.PARTIAL, pg.getCoverage());
    }

    @Test
    void matrix_insertSelect_none() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        meta.withTable("t2", "id");
        RollbackPlan plan = assessMySql("INSERT INTO t (a) SELECT b FROM t2", meta);
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.BATCH_INSERT, plan.getUnavailableReason());
    }

    @Test
    void matrix_onDuplicate_none() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql(
                "INSERT INTO t (id, a) VALUES (1, 2) ON DUPLICATE KEY UPDATE a = 2", meta);
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.BATCH_INSERT, plan.getUnavailableReason());
    }

    @Test
    void matrix_replace_none() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql("REPLACE INTO t (id, a) VALUES (1, 2)", meta);
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.BATCH_INSERT, plan.getUnavailableReason());
    }

    // ---------- §5.2 无主键行 ----------

    @Test
    void matrix_noPk_none() {
        Meta meta = new Meta();
        meta.withTable("t"); // 无主键
        RollbackPlan plan = assessMySql("UPDATE t SET a = 1 WHERE b = 2", meta, hit(RuleCode.R_NO_PK));
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.NO_PRIMARY_KEY, plan.getUnavailableReason());
    }

    // ---------- §5.2 函数/表达式 SET 行 ----------

    @Test
    void matrix_nondeterministicSet_none() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan plan = assessMySql("UPDATE t SET a = a + 1 WHERE id = 1", meta,
                hit(RuleCode.R_NONDETERMINISTIC_SET));
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.NONDETERMINISTIC, plan.getUnavailableReason());
    }

    @Test
    void matrix_multiTableAndLimitOrder_none() {
        Meta meta = new Meta();
        meta.withTable("t1", "id");
        meta.withTable("t2", "id");
        assertEquals(UnavailableReason.MULTI_TABLE_DML,
                assessMySql("UPDATE t1 JOIN t2 ON t1.id = t2.id SET t1.a = 1", meta,
                        hit(RuleCode.R_MULTI_TABLE_DML)).getUnavailableReason());
        assertEquals(UnavailableReason.LIMIT_ORDER_DML,
                assessMySql("DELETE FROM t1 WHERE id = 1 LIMIT 10", meta,
                        hit(RuleCode.R_LIMIT_ORDER_DML)).getUnavailableReason());
    }

    // ---------- §5.2 DDL 行 ----------

    @Test
    void matrix_reversibleDdl_full() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan create = assessMySql("CREATE TABLE t (id INT PRIMARY KEY)", meta);
        assertEquals(RollbackCoverage.FULL, create.getCoverage());
        assertEquals(RollbackGenerator.REVERSE_DDL, create.getGenerator());

        RollbackPlan addCol = assessMySql("ALTER TABLE t ADD COLUMN c INT", meta);
        assertEquals(RollbackCoverage.FULL, addCol.getCoverage());
    }

    @Test
    void matrix_destructiveDdl_none() {
        Meta meta = new Meta();
        meta.withTable("t", "id");
        RollbackPlan drop = assessMySql("DROP TABLE t", meta, hit(RuleCode.R_DDL_DESTRUCTIVE));
        assertEquals(RollbackCoverage.NONE, drop.getCoverage());
        assertEquals(UnavailableReason.DESTRUCTIVE_DDL, drop.getUnavailableReason());
        assertEquals(RollbackGenerator.SNAPSHOT, drop.getGenerator());
    }

    // ---------- §5.2 IoTDB 行 ----------

    @Test
    void matrix_iotdb_none() {
        List<ParsedStatement> units = parser.parse(
                "INSERT INTO root.sg.d1(timestamp, status) VALUES (1, true)", Dialect.IOTDB);
        RollbackPlan plan = assessor.assess(new CapabilityContext(units.get(0), List.of(), null));
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.IOTDB_UNSUPPORTED, plan.getUnavailableReason());
    }

    // ---------- 解析失败 ----------

    @Test
    void parseFailed_none() {
        RollbackPlan plan = assessMySql("SELEC * FORM t", new Meta());
        assertEquals(RollbackCoverage.NONE, plan.getCoverage());
        assertEquals(UnavailableReason.PARSE_FAILED, plan.getUnavailableReason());
    }
}
