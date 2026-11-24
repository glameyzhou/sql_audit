package com.sqlaudit.parser;

import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.postgres.PostgresDialectParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 解析 + 只读判定测试（README §4.3 危险子句表 PG 特有行）。
 * <p>临时实现基于 Druid PG 模式（S1 spike 前的过渡），解析失败一律高危。</p>
 */
class PostgresDialectParserTest {

    private final PostgresDialectParser parser = new PostgresDialectParser();

    private ParsedStatement one(String sql) {
        List<ParsedStatement> list = parser.parse(sql);
        assertEquals(1, list.size(), "应解析为单条语句: " + sql);
        return list.get(0);
    }

    @Test
    void forUpdate_viaForClause() {
        // 诊断实测：Druid PG 的 isForUpdate() 恒 false，FOR UPDATE 存在 forClause
        ParsedStatement ps = one("SELECT * FROM t WHERE id = 1 FOR UPDATE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FOR_UPDATE_LOCK));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void forShare_viaForClause() {
        ParsedStatement ps = one("SELECT * FROM t WHERE id = 1 FOR SHARE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FOR_UPDATE_LOCK));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void selectIntoNewTable_isNotReadOnly() {
        ParsedStatement ps = one("SELECT * INTO t_new FROM t");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.SELECT_INTO));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void dataModifyingCte_isNotReadOnly() {
        // README §4.3 第 5 行：WITH x AS (DELETE ... RETURNING *) 实际写表
        ParsedStatement ps = one("WITH x AS (DELETE FROM t WHERE id = 1 RETURNING *) SELECT * FROM x");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.DATA_MODIFYING_CTE));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void plainCte_isReadOnly_noFalsePositive() {
        ParsedStatement ps = one("WITH x AS (SELECT id FROM t2) SELECT * FROM x");
        assertTrue(ps.getDangerousClauses().isEmpty(), "普通 CTE 不应误报");
        assertTrue(ps.isReadOnly());
    }

    @Test
    void explainAnalyzeDml_isNotReadOnly() {
        ParsedStatement ps = one("EXPLAIN ANALYZE DELETE FROM t WHERE id = 1");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.EXPLAIN_ANALYZE));
        assertFalse(ps.isReadOnly(), "PG 的 EXPLAIN ANALYZE 会真实执行 DML");
    }

    @Test
    void plainExplain_isReadOnly() {
        // 查询控制台兜底能力：普通 EXPLAIN（看执行计划）必须只读放行（PG JDBC 返回 QUERY PLAN 结果集）
        ParsedStatement ps = one("EXPLAIN SELECT * FROM t WHERE id = 1");
        assertTrue(ps.getDangerousClauses().isEmpty());
        assertTrue(ps.isReadOnly());
        assertEquals(com.sqlaudit.common.enums.StatementType.SELECT, ps.getStatementType());
    }

    @Test
    void explainWithParenOptions_parseFailNeverDowngrade() {
        // Druid 1.2.23 解析不了 PG 的 EXPLAIN (ANALYZE, BUFFERS)——解析失败铁律：非只读
        ParsedStatement ps = one("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM t");
        assertFalse(ps.isReadOnly(), "解析失败绝不降级放行（ANALYZE 会真实执行）");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.LOW_CONFIDENCE));
    }

    @Test
    void deleteReturning_typeDelete() {
        ParsedStatement ps = one("DELETE FROM t WHERE id = 1 RETURNING *");
        assertEquals(com.sqlaudit.common.enums.StatementType.DELETE, ps.getStatementType());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void parseFailed_neverDowngrade() {
        ParsedStatement ps = one("SELECT FROM WHERE");
        assertEquals(0, ps.getParseConfidence());
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.LOW_CONFIDENCE));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void plainSelect_isReadOnly() {
        ParsedStatement ps = one("SELECT id FROM t WHERE id = 1");
        assertTrue(ps.isReadOnly());
        assertEquals(100, ps.getParseConfidence());
    }
}
