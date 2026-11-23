package com.sqlaudit.query.limit;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.sqlaudit.common.enums.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIMIT 注入测试（README §4.1 三层保护第 1 层 + 三种拼接出错 case）。
 */
class LimitInjectorTest {

    private final LimitInjector injector = new LimitInjector();

    // ------------------------------------------------------------------
    // 验收 case ①：已有 LIMIT ≤ 上限 → 不动，绝不产生 LIMIT 10 LIMIT 1000
    // ------------------------------------------------------------------

    @Test
    void case1_existingSmallLimit_untouched_noDoubleLimit() {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.PG)) {
            InjectionResult r = injector.inject(
                    "SELECT id, name FROM t ORDER BY id LIMIT 10", dialect, 1000);

            assertFalse(r.unsupported());
            assertFalse(r.injected());
            assertFalse(r.tightened());
            assertEquals(10, r.originalLimit());
            // 原样透传：不会出现 "LIMIT 10 LIMIT 1000"
            assertEquals("SELECT id, name FROM t ORDER BY id LIMIT 10", r.rewrittenSql());
            assertFalse(Pattern.compile("LIMIT\\s+\\d+\\s+LIMIT", Pattern.CASE_INSENSITIVE)
                    .matcher(r.rewrittenSql()).find(), "不允许出现双重 LIMIT: " + r.rewrittenSql());
            assertNull(r.notice());
        }
    }

    // ------------------------------------------------------------------
    // 验收 case ②：UNION 每个分支语义不变，LIMIT 只作用整体
    // ------------------------------------------------------------------

    @Test
    void case2_union_limitAppliesToWhole_notLastBranch() {
        // UNION 无 LIMIT → 注入在 SQLUnionQuery 整体，不碰分支
        InjectionResult r = injector.inject(
                "SELECT id FROM t1 UNION SELECT id FROM t2", Dialect.MYSQL, 1000);
        assertTrue(r.injected());
        assertEquals(1000, r.effectiveLimit());

        SQLUnionQuery union = parseUnion(r.rewrittenSql(), DbType.mysql);
        assertEquals(1000, limitValue(union));
        // 分支 LIMIT 必须为 null：只改最后一个分支会改变语义
        assertNull(((SQLSelectQueryBlock) union.getLeft()).getLimit(), "左分支 LIMIT 不应被修改");
        assertNull(((SQLSelectQueryBlock) union.getRight()).getLimit(), "右分支 LIMIT 不应被修改");
        assertTrue(r.rewrittenSql().toUpperCase().contains("UNION"));
        assertEquals("平台已注入 LIMIT 1000", r.notice());
    }

    @Test
    void case2_union_existingSmallLimit_untouched() {
        // UNION 已有整体 LIMIT 10（Druid 挂在 SQLUnionQuery 上）≤ 上限 → 不动
        InjectionResult r = injector.inject(
                "SELECT id FROM t1 UNION SELECT id FROM t2 LIMIT 10", Dialect.MYSQL, 1000);
        assertFalse(r.injected());
        assertFalse(r.tightened());
        assertEquals(10, r.originalLimit());
        assertEquals("SELECT id FROM t1 UNION SELECT id FROM t2 LIMIT 10", r.rewrittenSql());
    }

    @Test
    void case2_union_branchLimit_preserved_andInjectsOnWhole() {
        // 分支带括号 LIMIT（挂分支 block）：注入整体 LIMIT，分支语义不变
        InjectionResult r = injector.inject(
                "SELECT id FROM t1 UNION (SELECT id FROM t2 LIMIT 5)", Dialect.MYSQL, 1000);
        assertTrue(r.injected());

        SQLUnionQuery union = parseUnion(r.rewrittenSql(), DbType.mysql);
        assertEquals(1000, limitValue(union));
        assertNull(((SQLSelectQueryBlock) union.getLeft()).getLimit());
        assertEquals(5, limitValue((SQLSelectQueryBlock) union.getRight()));
    }

    @Test
    void case2_union_pg_sameBehavior() {
        InjectionResult r = injector.inject(
                "SELECT id FROM t1 UNION SELECT id FROM t2", Dialect.PG, 1000);
        assertTrue(r.injected());
        SQLUnionQuery union = parseUnion(r.rewrittenSql(), DbType.postgresql);
        assertEquals(1000, limitValue(union));
    }

    // ------------------------------------------------------------------
    // 验收 case ③：子查询重名列 → 直接在原 AST 上 setLimit，禁止外层包裹
    // ------------------------------------------------------------------

    @Test
    void case3_subqueryDuplicateColumns_noWrapping() {
        // t1.id / t2.id 重名列：若包裹 SELECT * FROM (...) 会报 Duplicate column name
        String sql = "SELECT * FROM (SELECT t1.id, t2.id FROM t1 JOIN t2 ON t1.id = t2.id) x ORDER BY 1";
        InjectionResult r = injector.inject(sql, Dialect.MYSQL, 1000);
        assertTrue(r.injected());

        // 结构断言：外层仍是原查询（selectList 仍为 *，FROM 仍是派生表 x），
        // 没有被包一层平台 wrapper
        SQLSelectStatement select = parseSelect(r.rewrittenSql(), DbType.mysql);
        SQLSelectQueryBlock block = select.getSelect().getQueryBlock();
        assertEquals(1, block.getSelectList().size(), "外层 select 列表结构不应变化");
        SQLSubqueryTableSource from = (SQLSubqueryTableSource) block.getFrom();
        assertEquals(2, from.getSelect().getQueryBlock().getSelectList().size(),
                "内层重名列 t1.id / t2.id 必须原样保留");
        assertEquals("x", from.getAlias().toLowerCase());
        assertEquals(1000, limitValue(block));
        assertTrue(r.rewrittenSql().toUpperCase().contains("LIMIT 1000"));
    }

    // ------------------------------------------------------------------
    // 注入 / 收紧 / unsupported
    // ------------------------------------------------------------------

    @Test
    void noLimit_injectsMaxLimit() {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.PG)) {
            InjectionResult r = injector.inject("SELECT id FROM t", dialect, 1000);
            assertTrue(r.injected());
            assertFalse(r.tightened());
            assertNull(r.originalLimit());
            assertEquals(1000, r.effectiveLimit());
            assertEquals("平台已注入 LIMIT 1000", r.notice());
        }
    }

    @Test
    void oversizedLimit_tightened() {
        InjectionResult r = injector.inject("SELECT id FROM t LIMIT 5000", Dialect.MYSQL, 1000);
        assertFalse(r.injected());
        assertTrue(r.tightened());
        assertEquals(5000, r.originalLimit());
        assertEquals(1000, r.effectiveLimit());
        assertTrue(Pattern.compile("LIMIT\\s+1000").matcher(r.rewrittenSql()).find());
        assertFalse(Pattern.compile("LIMIT\\s+5000").matcher(r.rewrittenSql()).find());
        assertEquals("原 LIMIT 5000 超出上限，平台已收紧为 LIMIT 1000", r.notice());

        // 收紧后 SQL 语义：LIMIT 值确实是 1000
        assertEquals(1000, limitValue(parseSelect(r.rewrittenSql(), DbType.mysql)
                .getSelect().getQueryBlock()));
    }

    @Test
    void oversizedLimit_mysqlOffsetForm_keepsOffset() {
        // MySQL LIMIT offset, count 形态：收紧 count，保留 offset
        InjectionResult r = injector.inject("SELECT id FROM t LIMIT 100, 5000", Dialect.MYSQL, 1000);
        assertTrue(r.tightened());
        assertTrue(Pattern.compile("LIMIT\\s+100,\\s*1000").matcher(r.rewrittenSql()).find(),
                "offset 100 必须保留: " + r.rewrittenSql());
    }

    @Test
    void parseFailure_unsupported() {
        InjectionResult r = injector.inject("SELECT * FROM", Dialect.MYSQL, 1000);
        assertTrue(r.unsupported());
        assertFalse(r.injected());
        assertFalse(r.tightened());
        assertEquals("SELECT * FROM", r.rewrittenSql());
        assertNull(r.notice());
    }

    @Test
    void multiStatement_unsupported() {
        InjectionResult r = injector.inject("SELECT 1; SELECT 2", Dialect.MYSQL, 1000);
        assertTrue(r.unsupported());
    }

    @Test
    void nonSelect_unsupported() {
        // 门禁只放行 SELECT，但注入层必须自防：UPDATE / EXPLAIN 到达即 unsupported
        assertTrue(injector.inject("UPDATE t SET a = 1", Dialect.MYSQL, 1000).unsupported());
        assertTrue(injector.inject("EXPLAIN SELECT * FROM t", Dialect.MYSQL, 1000).unsupported());
    }

    @Test
    void iotdb_unsupported() {
        // IoTDB 白名单 parser 不产 Druid AST → unsupported → setMaxRows 兜底
        InjectionResult r = injector.inject("SELECT s1 FROM root.sg.d1", Dialect.IOTDB, 1000);
        assertTrue(r.unsupported());
        assertEquals("SELECT s1 FROM root.sg.d1", r.rewrittenSql());
    }

    // ------------------------------------------------------------------
    // helper：重新解析注入结果验证 AST 事实
    // ------------------------------------------------------------------

    private SQLSelectStatement parseSelect(String sql, DbType dbType) {
        try {
            List<SQLStatement> list = SQLParserUtils
                    .createSQLStatementParser(sql, dbType).parseStatementList();
            assertEquals(1, list.size());
            return (SQLSelectStatement) list.get(0);
        } catch (Exception e) {
            throw new AssertionError("改写后 SQL 无法解析: " + sql, e);
        }
    }

    private SQLUnionQuery parseUnion(String sql, DbType dbType) {
        return (SQLUnionQuery) parseSelect(sql, dbType).getSelect().getQuery();
    }

    private int limitValue(SQLUnionQuery union) {
        return ((SQLIntegerExpr) union.getLimit().getRowCount()).getNumber().intValue();
    }

    private int limitValue(SQLSelectQueryBlock block) {
        return ((SQLIntegerExpr) block.getLimit().getRowCount()).getNumber().intValue();
    }
}
