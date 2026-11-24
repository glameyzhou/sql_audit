package com.sqlaudit.query.gate;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.SqlObject;
import com.sqlaudit.parser.SqlParserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例默认库边界校验测试（MySQL 限定名越界；PG 不校验）。
 *
 * <p>用真 parser 抽取对象引用，覆盖「CTE 别名不误杀」「剥引号后匹配」等真实形态。</p>
 */
class SchemaBoundaryCheckerTest {

    private final SqlParserService parser = new SqlParserService();

    private List<SqlObject> objects(String sql, Dialect dialect) {
        return parser.parse(sql, dialect).get(0).getObjects();
    }

    @Test
    void mysql_otherSchema_rejected() {
        List<String> v = SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx",
                objects("SELECT * FROM other_db.t", Dialect.MYSQL));
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("other_db.t"));
        assertTrue(v.get(0).contains("jx"));
    }

    @Test
    void mysql_bareTable_allowed() {
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx",
                objects("SELECT * FROM t", Dialect.MYSQL)).isEmpty());
    }

    @Test
    void mysql_expectedSchema_allowed() {
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx",
                objects("SELECT * FROM jx.t", Dialect.MYSQL)).isEmpty());
        // 剥引号后匹配
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx",
                objects("SELECT * FROM `jx`.`t`", Dialect.MYSQL)).isEmpty());
    }

    @Test
    void mysql_noDefault_noRestriction() {
        // 实例未配置默认库：无边界锚点，限定名是唯一引用库的方式 → 不限制
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, null,
                objects("SELECT * FROM other.t", Dialect.MYSQL)).isEmpty());
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "",
                objects("SELECT * FROM other.t", Dialect.MYSQL)).isEmpty());
    }

    @Test
    void pg_neverRejected() {
        assertTrue(SchemaBoundaryChecker.violations(Dialect.PG, "jx",
                objects("SELECT * FROM public.t", Dialect.PG)).isEmpty());
        assertTrue(SchemaBoundaryChecker.violations(Dialect.PG, "jx",
                objects("SELECT * FROM t", Dialect.PG)).isEmpty());
    }

    @Test
    void cteAlias_notCountedAsObject() {
        // WITH x AS (...) SELECT * FROM x —— x 是 CTE 别名，不产生表引用对象
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx",
                objects("WITH x AS (SELECT 1) SELECT * FROM x", Dialect.MYSQL)).isEmpty());
    }

    @Test
    void emptyObjects_allowed() {
        assertTrue(SchemaBoundaryChecker.violations(Dialect.MYSQL, "jx", List.of()).isEmpty());
    }
}
