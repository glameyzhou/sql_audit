package com.sqlaudit.parser;

import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.SqlObject;
import com.sqlaudit.parser.mysql.MySqlDialectParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 解析 + 只读判定测试（验收：README §4.3 危险子句表每一行都有用例；
 * 只读判定假阴性率为 0——宁可误判为写操作）。
 */
class MySqlDialectParserTest {

    private final MySqlDialectParser parser = new MySqlDialectParser();

    private ParsedStatement one(String sql) {
        List<ParsedStatement> list = parser.parse(sql);
        assertEquals(1, list.size(), "应解析为单条语句: " + sql);
        return list.get(0);
    }

    // ---------------- README §4.3 危险子句表逐行 ----------------

    @Test
    void row1_explainAnalyze_isNotReadOnly() {
        ParsedStatement ps = one("EXPLAIN ANALYZE SELECT * FROM t WHERE id = 1");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.EXPLAIN_ANALYZE));
        assertFalse(ps.isReadOnly(), "EXPLAIN ANALYZE 真实执行，必须非只读");
    }

    @Test
    void row1_plainExplain_isReadOnly() {
        // README §4.3：dry-run 只用不带 ANALYZE 的 EXPLAIN——普通 EXPLAIN 只读
        ParsedStatement ps = one("EXPLAIN SELECT * FROM t WHERE id = 1");
        assertTrue(ps.getDangerousClauses().isEmpty());
        assertTrue(ps.isReadOnly());
        assertEquals(StatementType.SELECT, ps.getStatementType());
    }

    @Test
    void row2_forUpdate_isNotReadOnly() {
        ParsedStatement ps = one("SELECT * FROM t WHERE id = 1 FOR UPDATE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FOR_UPDATE_LOCK));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row2_forShare_isNotReadOnly() {
        ParsedStatement ps = one("SELECT * FROM t WHERE id = 1 FOR SHARE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FOR_UPDATE_LOCK));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row2_lockInShareMode_isNotReadOnly() {
        ParsedStatement ps = one("SELECT * FROM t WHERE id = 1 LOCK IN SHARE MODE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FOR_UPDATE_LOCK));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row3_intoOutfile_isNotReadOnly() {
        ParsedStatement ps = one("SELECT * FROM t INTO OUTFILE '/tmp/x'");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.INTO_OUTFILE));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row3_intoDumpfile_parseFailSafe() {
        // Druid 解析不了 INTO DUMPFILE → 铁律兜底：confidence=0 高危
        ParsedStatement ps = one("SELECT * FROM t INTO DUMPFILE '/tmp/x'");
        assertFalse(ps.isReadOnly());
        assertEquals(0, ps.getParseConfidence());
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.LOW_CONFIDENCE));
    }

    @Test
    void row3_intoVar_isNotReadOnly() {
        ParsedStatement ps = one("SELECT a INTO @v FROM t");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.INTO_OUTFILE));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row6_functionSideEffect_sleep_isNotReadOnly() {
        ParsedStatement ps = one("SELECT SLEEP(5)");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.FUNCTION_SIDE_EFFECT));
        assertFalse(ps.isReadOnly());
        assertTrue(ps.isContainsFunction());
    }

    @Test
    void row6_pureFunctionNow_isReadOnly() {
        // NOW() 属语句级恒定纯函数（白名单），不误伤
        ParsedStatement ps = one("SELECT * FROM t WHERE created_at > NOW() - INTERVAL 1 DAY");
        assertTrue(ps.getDangerousClauses().isEmpty(), "白名单函数不应触发 FUNCTION_SIDE_EFFECT");
        assertTrue(ps.isReadOnly());
        assertTrue(ps.isContainsFunction());
    }

    @Test
    void row7_call_isSessionMutation() {
        ParsedStatement ps = one("CALL do_something()");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.SESSION_MUTATION));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row7_set_isSessionMutation() {
        ParsedStatement ps = one("SET autocommit = 0");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.SESSION_MUTATION));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row7_lockTables_isSessionMutation() {
        // MySqlLockTableStatement（方言类名），验证类名判定不漏
        ParsedStatement ps = one("LOCK TABLES t WRITE");
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.SESSION_MUTATION));
        assertFalse(ps.isReadOnly());
    }

    @Test
    void row8_parseFailed_neverDowngrade() {
        ParsedStatement ps = one("SELEC * FORM t");
        assertEquals(0, ps.getParseConfidence());
        assertTrue(ps.getDangerousClauses().contains(DangerousClause.LOW_CONFIDENCE));
        assertFalse(ps.isReadOnly(), "解析失败绝不降级放行");
    }

    // ---------------- 归一化与正常路径 ----------------

    @Test
    void plainSelect_isReadOnly() {
        ParsedStatement ps = one("SELECT id, name FROM t_user WHERE id = 1");
        assertTrue(ps.isReadOnly());
        assertEquals(StatementType.SELECT, ps.getStatementType());
        assertEquals(100, ps.getParseConfidence());
        assertEquals(1, ps.getObjects().size());
        assertEquals("t_user", ps.getObjects().get(0).getName());
        assertNotNull(ps.getWhereAst(), "WHERE AST 应填充");
    }

    @Test
    void selectWithSubquery_isReadOnly() {
        // 子查询 SELECT 本身无写副作用（README §4.3：排除的是「子查询写副作用」）
        ParsedStatement ps = one("SELECT * FROM t WHERE id IN (SELECT id FROM t2)");
        assertTrue(ps.isReadOnly());
        assertTrue(ps.isContainsSubquery());
    }

    @Test
    void updateType_and_object() {
        ParsedStatement ps = one("UPDATE t_user SET name = 'x' WHERE id = 1");
        assertEquals(StatementType.UPDATE, ps.getStatementType());
        assertFalse(ps.isReadOnly());
        assertEquals("t_user", ps.getObjects().get(0).getName());
    }

    @Test
    void insertAndDeleteTypes() {
        assertEquals(StatementType.INSERT, one("INSERT INTO t(a, b) VALUES (1, 2)").getStatementType());
        assertEquals(StatementType.DELETE, one("DELETE FROM t WHERE id = 1").getStatementType());
    }

    @Test
    void ddlTypes() {
        assertEquals(StatementType.DDL, one("CREATE TABLE t (id INT PRIMARY KEY)").getStatementType());
        assertEquals(StatementType.DDL, one("ALTER TABLE t ADD COLUMN c INT").getStatementType());
        assertEquals(StatementType.DDL, one("DROP TABLE t").getStatementType());
        assertEquals(StatementType.DDL, one("TRUNCATE TABLE t").getStatementType());
    }

    @Test
    void multiStatementSplit_oneStatementOneUnit() {
        List<ParsedStatement> list = parser.parse("SELECT 1; UPDATE t SET a = 1 WHERE id = 1; SELECT 2");
        assertEquals(3, list.size(), "一条语句 = 一个 unit");
        assertTrue(list.get(0).isReadOnly());
        assertFalse(list.get(1).isReadOnly());
        assertTrue(list.get(2).isReadOnly());
    }

    @Test
    void cteAlias_notCollectedAsTable() {
        // WITH x AS (...) SELECT * FROM x JOIN t3 —— x 是 CTE 别名不是表
        ParsedStatement ps = one("WITH x AS (SELECT id FROM t2) SELECT * FROM x JOIN t3");
        List<String> names = ps.getObjects().stream().map(SqlObject::getName).toList();
        assertTrue(names.contains("t2"));
        assertTrue(names.contains("t3"));
        assertFalse(names.contains("x"), "CTE 别名不应计入表对象");
        assertTrue(ps.isReadOnly(), "普通 CTE 只读");
    }

    @Test
    void schemaQualifiedTable_collected() {
        ParsedStatement ps = one("SELECT * FROM mydb.t_user");
        assertEquals("mydb", ps.getObjects().get(0).getSchema());
        assertEquals("t_user", ps.getObjects().get(0).getName());
    }

    @Test
    void updateWithNonPureFunction_containsFunction() {
        ParsedStatement ps = one("UPDATE t SET a = RAND() WHERE id = 1");
        assertTrue(ps.isContainsFunction());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void showTables_notReadOnly() {
        // 只读白名单只认 SELECT（README §4.3），SHOW 走 OTHER 非只读
        ParsedStatement ps = one("SHOW TABLES");
        assertEquals(StatementType.OTHER, ps.getStatementType());
        assertFalse(ps.isReadOnly());
    }
}
