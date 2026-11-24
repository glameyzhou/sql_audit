package com.sqlaudit.query.console;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.query.console.ConsoleCommandRewriter.Rewrite;
import com.sqlaudit.query.gate.QueryGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台实用语句翻译测试（SHOW TABLES / DESC → information_schema 只读 SELECT）。
 *
 * <p>验收重点：①识别形态严格（多余子句/注入尝试一律透传给门禁）；
 * ②翻译产物必须能过 {@link QueryGate} 只读判定（模板回归防线：谁往模板里加
 * {@code DATABASE()} 这类白名单外函数，这里立刻红）；
 * ③PG 裸标识符小写折叠、引号标识符保精确、单引号转义。</p>
 */
class ConsoleCommandRewriterTest {

    private final QueryGate gate = new QueryGate();

    private Rewrite rewrite(String sql, Dialect dialect) {
        return ConsoleCommandRewriter.rewrite(sql, dialect, "jx");
    }

    // ---------------- SHOW TABLES ----------------

    @Test
    void showTables_mysql_translatedAndPassesGate() {
        Rewrite r = rewrite("show tables", Dialect.MYSQL);
        assertTrue(r.isTranslated());
        assertNull(r.rejectReason());
        assertTrue(r.sql().contains("table_schema = 'jx'"), r.sql());
        assertTrue(gate.check(r.sql(), Dialect.MYSQL).allowed(), "翻译产物必须是只读 SELECT");
    }

    @Test
    void showTables_pg_translatedAndPassesGate() {
        Rewrite r = rewrite("SHOW TABLES;", Dialect.PG);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("\"table_name\""), "PG 别名用双引号: " + r.sql());
        assertTrue(gate.check(r.sql(), Dialect.PG).allowed());
    }

    @Test
    void showTables_blankSchema_rejected() {
        Rewrite r = ConsoleCommandRewriter.rewrite("show tables", Dialect.MYSQL, " ");
        assertNotNull(r.rejectReason());
        assertNull(r.sql());
    }

    // ---------------- DESC / DESCRIBE ----------------

    @Test
    void desc_mysql_bareIdent_translatedAndPassesGate() {
        Rewrite r = rewrite("DESC users", Dialect.MYSQL);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("table_name = 'users'"), r.sql());
        assertTrue(r.sql().contains("`comment`"), "MySQL 带字段注释列: " + r.sql());
        assertTrue(gate.check(r.sql(), Dialect.MYSQL).allowed());
    }

    @Test
    void desc_pg_bareIdent_foldedLowercase() {
        Rewrite r = rewrite("DESCRIBE Users", Dialect.PG);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("table_name = 'users'"), "PG 裸标识符折叠小写: " + r.sql());
        assertTrue(gate.check(r.sql(), Dialect.PG).allowed());
    }

    @Test
    void desc_mysql_backtickIdent_keepsCase() {
        Rewrite r = rewrite("DESC `Order`", Dialect.MYSQL);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("table_name = 'Order'"), "反引号标识符保持精确大小写: " + r.sql());
    }

    @Test
    void desc_pg_doubleQuotedIdent_keepsCase() {
        Rewrite r = rewrite("DESC \"Users\"", Dialect.PG);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("table_name = 'Users'"), "双引号标识符保持精确大小写: " + r.sql());
    }

    @Test
    void desc_quotedNameWithQuote_escaped() {
        Rewrite r = rewrite("DESC \"o'brien\"", Dialect.MYSQL);
        assertTrue(r.isTranslated());
        assertTrue(r.sql().contains("table_name = 'o''brien'"), "单引号必须转义: " + r.sql());
    }

    @Test
    void desc_blankSchema_rejected() {
        Rewrite r = ConsoleCommandRewriter.rewrite("DESC users", Dialect.PG, null);
        assertNotNull(r.rejectReason());
    }

    // ---------------- 透传（识别不了的原样交给门禁） ----------------

    @Test
    void passthrough_plainSelect() {
        Rewrite r = rewrite("SELECT * FROM users", Dialect.MYSQL);
        assertFalse(r.isTranslated());
        assertNull(r.rejectReason());
        assertEquals("SELECT * FROM users", r.sql());
    }

    @Test
    void passthrough_explainNeedsNoTranslation() {
        String sql = "EXPLAIN SELECT * FROM users WHERE id = 1";
        assertEquals(sql, rewrite(sql, Dialect.MYSQL).sql());
        assertEquals(sql, rewrite(sql, Dialect.PG).sql());
    }

    @Test
    void passthrough_showTablesWithExtraClause() {
        String sql = "SHOW TABLES FROM other_db";
        Rewrite r = rewrite(sql, Dialect.MYSQL);
        assertFalse(r.isTranslated());
        assertEquals(sql, r.sql());
    }

    @Test
    void passthrough_descWithColumnPattern() {
        String sql = "DESC users col";
        assertFalse(rewrite(sql, Dialect.MYSQL).isTranslated());
    }

    @Test
    void passthrough_injectionAttempt() {
        // 形态白名单外的注入串必须原样透传（后续门禁拒绝），绝不进入翻译分支
        String[] attacks = {
                "DESC users; DROP TABLE users",
                "SHOW TABLES; SELECT * FROM t",
                "DESC users-- comment",
                "/*c*/ SHOW TABLES",
                "DESC users INTO OUTFILE '/tmp/x'",
        };
        for (String sql : attacks) {
            Rewrite r = rewrite(sql, Dialect.MYSQL);
            assertFalse(r.isTranslated(), "不得翻译: " + sql);
            assertNull(r.rejectReason());
            assertEquals(sql, r.sql());
        }
    }

    @Test
    void passthrough_iotdbAndNull() {
        assertEquals("show tables", ConsoleCommandRewriter.rewrite("show tables", Dialect.IOTDB, "jx").sql());
        assertEquals("x", ConsoleCommandRewriter.rewrite("x", null, "jx").sql());
        assertNull(ConsoleCommandRewriter.rewrite(null, Dialect.MYSQL, "jx").sql());
    }
}
