package com.sqlaudit.rule;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.engine.AuditOutcome;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.parser.SqlParserService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎表驱动测试（A-2 验收：§4.3 规则逐条用例）。
 */
class RuleEngineTest {

    private final SqlParserService parserService = new SqlParserService();

    /** 内存元数据：默认所有表有主键、在白名单、无掩码列。 */
    private static final class FakeMetadata implements TableMetadataProvider {
        final Map<String, TableMetadata> tables = new HashMap<>();
        boolean available = true;

        void put(String name, boolean hasPk) {
            tables.put(name, new TableMetadata(hasPk, hasPk ? List.of("id") : List.of(),
                    Set.of(), List.of("id"), Set.of(), true));
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public TableMetadata getTable(String schema, String name) {
            return tables.get(name);
        }
    }

    private RuleEngine engineWith(FakeMetadata meta) {
        return new RuleEngine(meta);
    }

    private FakeMetadata defaultMeta() {
        FakeMetadata meta = new FakeMetadata();
        meta.put("t", true);
        meta.put("t_user", true);
        meta.put("t1", true);
        meta.put("t2", true);
        return meta;
    }

    private List<RuleHit> auditMySql(FakeMetadata meta, String sql) {
        List<ParsedStatement> units = parserService.parse(sql, Dialect.MYSQL);
        RuleEngine engine = engineWith(meta);
        return engine.audit(units).allHits();
    }

    private boolean hasCode(List<RuleHit> hits, String code) {
        return hits.stream().anyMatch(h -> code.equals(h.getCode()));
    }

    // ---------------- AST DML 规则 ----------------

    @Test
    void rNoWhere_updateWithoutWhere() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1"), "R_NO_WHERE"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1 WHERE id = 1"), "R_NO_WHERE"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "DELETE FROM t"), "R_NO_WHERE"));
    }

    @Test
    void rLimitOrderDml_updateWithLimit() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1 WHERE id = 1 LIMIT 10"),
                "R_LIMIT_ORDER_DML"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1 WHERE id = 1 ORDER BY id"),
                "R_LIMIT_ORDER_DML"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "DELETE FROM t WHERE id = 1 LIMIT 10"),
                "R_LIMIT_ORDER_DML"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1 WHERE id = 1"),
                "R_LIMIT_ORDER_DML"));
    }

    @Test
    void rNondeterministicSet_selfIncrement() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = a + 1 WHERE id = 1"),
                "R_NONDETERMINISTIC_SET"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET b = NOW() WHERE id = 1"),
                "R_NONDETERMINISTIC_SET"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = (SELECT MAX(x) FROM t2) WHERE id = 1"),
                "R_NONDETERMINISTIC_SET"));
        // 全字面量 SET 不命中
        assertFalse(hasCode(auditMySql(defaultMeta(), "UPDATE t SET a = 1, b = 'x' WHERE id = 1"),
                "R_NONDETERMINISTIC_SET"));
    }

    @Test
    void rMultiTableDml_joinUpdate() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "UPDATE t1 JOIN t2 ON t1.id = t2.id SET t1.a = 1"),
                "R_MULTI_TABLE_DML"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "DELETE t1 FROM t1 JOIN t2 ON t1.id = t2.id WHERE t2.x = 1"),
                "R_MULTI_TABLE_DML"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "UPDATE t1 SET a = 1 WHERE id = 1"),
                "R_MULTI_TABLE_DML"));
    }

    @Test
    void rSelectStar_insertSelectStar() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "INSERT INTO t1 SELECT * FROM t2"), "R_SELECT_STAR"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "INSERT INTO t1(a) SELECT b FROM t2"), "R_SELECT_STAR"));
    }

    // ---------------- DDL 规则 ----------------

    @Test
    void rDdlDestructive_dropTruncateAlter() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "DROP TABLE t"), "R_DDL_DESTRUCTIVE"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "TRUNCATE TABLE t"), "R_DDL_DESTRUCTIVE"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "ALTER TABLE t DROP COLUMN c1"), "R_DDL_DESTRUCTIVE"));
        assertTrue(hasCode(auditMySql(defaultMeta(), "ALTER TABLE t MODIFY COLUMN c1 VARCHAR(32)"),
                "R_DDL_DESTRUCTIVE"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "ALTER TABLE t ADD COLUMN c2 INT"), "R_DDL_DESTRUCTIVE"));
    }

    @Test
    void rDdlNoIfExists_dropWithoutIf() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "DROP TABLE t"), "R_DDL_NO_IF_EXISTS"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "DROP TABLE IF EXISTS t"), "R_DDL_NO_IF_EXISTS"));
    }

    @Test
    void rTableName_badNaming() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "CREATE TABLE BadName (id INT)"), "R_TABLE_NAME"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "CREATE TABLE good_name (id INT)"), "R_TABLE_NAME"));
    }

    @Test
    void rIndexName_noPrefix() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "CREATE INDEX my_idx ON t (a)"), "R_INDEX_NAME"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "CREATE INDEX idx_a ON t (a)"), "R_INDEX_NAME"));
    }

    // ---------------- 元数据规则 ----------------

    @Test
    void rNoPk_tableWithoutPrimaryKey() {
        FakeMetadata meta = defaultMeta();
        meta.put("nopk", false);
        assertTrue(hasCode(auditMySql(meta, "UPDATE nopk SET a = 1 WHERE id = 1"), "R_NO_PK"));
        assertFalse(hasCode(auditMySql(meta, "UPDATE t SET a = 1 WHERE id = 1"), "R_NO_PK"));
    }

    @Test
    void rNoPk_metadataUnavailable_conservativeBlock() {
        FakeMetadata meta = defaultMeta();
        meta.available = false;
        // 元数据不可达 → 无法证明可回滚 → 保守阻断
        assertTrue(hasCode(auditMySql(meta, "UPDATE t SET a = 1 WHERE id = 1"), "R_NO_PK"));
    }

    @Test
    void rObjectNotAllowed_notInWhitelist() {
        FakeMetadata meta = defaultMeta();
        meta.tables.put("t", new TableMetadataProvider.TableMetadata(true, List.of("id"),
                Set.of(), List.of("id"), Set.of(), false)); // t 移出白名单
        assertTrue(hasCode(auditMySql(meta, "UPDATE t SET a = 1 WHERE id = 1"), "R_OBJECT_NOT_ALLOWED"));
    }

    @Test
    void rSensitiveColumn_maskedColumn() {
        FakeMetadata meta = defaultMeta();
        meta.tables.put("t", new TableMetadataProvider.TableMetadata(true, List.of("id"),
                Set.of(), List.of("id", "phone"), Set.of("phone"), true));
        assertTrue(hasCode(auditMySql(meta, "UPDATE t SET a = 1 WHERE id = 1"), "R_SENSITIVE_COLUMN"));
    }

    // ---------------- 其他 AST/文本规则 ----------------

    @Test
    void rParseFailed_garbageSql() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "SELEC * FORM t"), "R_PARSE_FAILED"));
    }

    @Test
    void rSqlInjectionPattern_comments() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "SELECT a FROM t WHERE id = 1 -- AND x = 2"),
                "R_SQL_INJECTION_PATTERN"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "SELECT a FROM t WHERE id = 1"),
                "R_SQL_INJECTION_PATTERN"));
    }

    @Test
    void rNoLimitQuery_selectWithoutLimit() {
        assertTrue(hasCode(auditMySql(defaultMeta(), "SELECT a FROM t"), "R_NO_LIMIT_QUERY"));
        assertFalse(hasCode(auditMySql(defaultMeta(), "SELECT a FROM t LIMIT 10"), "R_NO_LIMIT_QUERY"));
    }

    @Test
    void rTxDdlMix_mixedTicket() {
        List<ParsedStatement> units = parserService.parse(
                "UPDATE t SET a = 1 WHERE id = 1; ALTER TABLE t ADD COLUMN c INT", Dialect.MYSQL);
        AuditOutcome outcome = engineWith(defaultMeta()).audit(units);
        assertTrue(outcome.allHits().stream().anyMatch(h -> "R_TX_DDL_MIX".equals(h.getCode())),
                "混合 DDL 与 DML 的工单应命中 R_TX_DDL_MIX");
        // 纯 DML 工单不命中
        List<ParsedStatement> dmlOnly = parserService.parse(
                "UPDATE t SET a = 1 WHERE id = 1; DELETE FROM t WHERE id = 2", Dialect.MYSQL);
        assertFalse(engineWith(defaultMeta()).audit(dmlOnly).allHits().stream()
                .anyMatch(h -> "R_TX_DDL_MIX".equals(h.getCode())));
    }

    @Test
    void rBigUnitCount_overThreshold() {
        // 阈值 200：构造 201 条
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                sql.append("; ");
            }
            sql.append("SELECT ").append(i).append(" FROM t LIMIT 1");
        }
        List<ParsedStatement> units = parserService.parse(sql.toString(), Dialect.MYSQL);
        assertTrue(engineWith(defaultMeta()).audit(units).allHits().stream()
                .anyMatch(h -> "R_BIG_UNIT_COUNT".equals(h.getCode())));
    }

    // ---------------- IoTDB 规则 ----------------

    @Test
    void iotdbRules() {
        RuleEngine engine = engineWith(defaultMeta());
        List<ParsedStatement> drop = parserService.parse("DROP TIMESERIES root.sg.d1.status", Dialect.IOTDB);
        assertTrue(engine.audit(drop).allHits().stream()
                .anyMatch(h -> "R_IOTDB_DROP".equals(h.getCode())));

        List<ParsedStatement> delNoRange = parserService.parse("DELETE FROM root.sg.d1", Dialect.IOTDB);
        assertTrue(engine.audit(delNoRange).allHits().stream()
                .anyMatch(h -> "R_IOTDB_NO_TIME_RANGE".equals(h.getCode())));

        List<ParsedStatement> ttl = parserService.parse("SET TTL TO root.sg 3600", Dialect.IOTDB);
        assertTrue(engine.audit(ttl).allHits().stream()
                .anyMatch(h -> "R_IOTDB_TTL".equals(h.getCode()) || "R_PARSE_FAILED".equals(h.getCode())));
    }

    // ---------------- 影响面规则（两阶段） ----------------

    @Test
    void impactRules_twoPhase() {
        RuleEngine engine = engineWith(defaultMeta());
        List<ParsedStatement> units = parserService.parse("UPDATE t SET a = 1 WHERE id = 1", Dialect.MYSQL);

        // 首轮同步：影响面规则不触发
        AuditOutcome first = engine.audit(units);
        assertFalse(first.allHits().stream().anyMatch(h -> "R_AFFECT_TOO_MANY".equals(h.getCode())));

        // 二次评估：10 万+1 行 → R_AFFECT_TOO_MANY（ERROR）+ R_LOW_PEAK_WINDOW（WARN）
        List<RuleHit> impactHits = engine.assessImpact(units.get(0), units, ImpactEstimate.of(100_001));
        assertTrue(impactHits.stream().anyMatch(h -> "R_AFFECT_TOO_MANY".equals(h.getCode())
                && h.getSeverity() == RuleSeverity.ERROR));
        assertTrue(impactHits.stream().anyMatch(h -> "R_LOW_PEAK_WINDOW".equals(h.getCode())
                && h.getSeverity() == RuleSeverity.WARN));

        // 5000+1 行 → 只 LOW_PEAK（未达阻断与镜像上限）
        List<RuleHit> mid = engine.assessImpact(units.get(0), units, ImpactEstimate.of(5_001));
        assertFalse(mid.stream().anyMatch(h -> "R_AFFECT_TOO_MANY".equals(h.getCode())));
        assertTrue(mid.stream().anyMatch(h -> "R_LOW_PEAK_WINDOW".equals(h.getCode())));
        // 5000 是 PREIMAGE 上限，5001 触发
        assertTrue(mid.stream().anyMatch(h -> "R_PREIMAGE_TOO_LARGE".equals(h.getCode())));

        // 100 行 → 干净
        assertTrue(engine.assessImpact(units.get(0), units, ImpactEstimate.of(100)).isEmpty());
    }

    // ---------------- 阻断标志 ----------------

    @Test
    void errorBlockedFlag() {
        List<ParsedStatement> units = parserService.parse("UPDATE t SET a = 1", Dialect.MYSQL);
        AuditOutcome outcome = engineWith(defaultMeta()).audit(units);
        assertTrue(outcome.errorBlocked(), "R_NO_WHERE ERROR 应阻断");
        assertEquals(RuleSeverity.ERROR, outcome.maxSeverity());
    }
}
