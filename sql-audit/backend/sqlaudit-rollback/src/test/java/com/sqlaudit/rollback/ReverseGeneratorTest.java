package com.sqlaudit.rollback;

import com.sqlaudit.rollback.reverse.DdlReverseMapper;
import com.sqlaudit.rollback.reverse.ReverseSqlGenerator;
import com.sqlaudit.rollback.reverse.ReverseStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向 SQL 生成 + DDL 逆向映射测试（README §4.4）。
 */
class ReverseGeneratorTest {

    private final ReverseSqlGenerator generator = new ReverseSqlGenerator();
    private final DdlReverseMapper ddlMapper = new DdlReverseMapper();

    @Test
    void updateReverse_perRowParameterized() {
        // 前镜像两行（列序 id,a,b）
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "a", 10, "b", "x"),
                Map.of("id", 2, "a", 20, "b", "y"));

        List<ReverseStatement> result = generator.forUpdate("t",
                List.of("id", "a", "b"), rows, List.of("a", "b"), List.of("id"));

        assertEquals(2, result.size(), "每前镜像行一条反向 UPDATE");
        assertEquals("UPDATE t SET a = ?, b = ? WHERE id = ?", result.get(0).sql());
        assertEquals(List.of(10, "x", 1), result.get(0).params(), "参数序：SET 旧值 + PK");
        assertEquals(List.of(20, "y", 2), result.get(1).params());
    }

    @Test
    void deleteReverse_perRowInsert() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "a", 10, "b", "x"));

        List<ReverseStatement> result = generator.forDelete("t",
                List.of("id", "a", "b"), rows);

        assertEquals(1, result.size());
        assertEquals("INSERT INTO t (id, a, b) VALUES (?, ?, ?)", result.get(0).sql());
        assertEquals(List.of(1, 10, "x"), result.get(0).params());
    }

    @Test
    void insertReverse_deleteByPk() {
        List<ReverseStatement> result = generator.forInsert("t", List.of("id"), List.of(42));
        assertEquals(1, result.size());
        assertEquals("DELETE FROM t WHERE id = ?", result.get(0).sql());
        assertEquals(List.of(42), result.get(0).params());
    }

    @Test
    void displaySql_quotesEscaped() {
        ReverseStatement stmt = new ReverseStatement(
                "INSERT INTO t (a) VALUES (?)", List.of("o'brien"));
        assertEquals("INSERT INTO t (a) VALUES ('o''brien')", stmt.displaySql());
    }

    // ---------------- DDL 逆向映射（README §4.4 表） ----------------

    @Test
    void ddlCreateTable_reverseDrop() {
        var sql = com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement("CREATE TABLE t (id INT PRIMARY KEY)");
        var reverse = ddlMapper.map(sql, com.sqlaudit.common.enums.Dialect.MYSQL);
        assertTrue(reverse.isPresent());
        assertEquals("DROP TABLE IF EXISTS t", reverse.get().reverseSql());
        assertTrue(reverse.get().precondition().contains("未被写入"));
    }

    @Test
    void ddlAddColumn_reverseDropColumn() {
        var sql = com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement("ALTER TABLE t ADD COLUMN c INT");
        var reverse = ddlMapper.map(sql, com.sqlaudit.common.enums.Dialect.MYSQL);
        assertTrue(reverse.isPresent());
        assertEquals("ALTER TABLE t DROP COLUMN c", reverse.get().reverseSql());
    }

    @Test
    void ddlCreateIndex_dialectSplit() {
        var mysql = com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement("CREATE INDEX idx_a ON t (a)");
        assertEquals("ALTER TABLE t DROP INDEX idx_a",
                ddlMapper.map(mysql, com.sqlaudit.common.enums.Dialect.MYSQL).orElseThrow().reverseSql());

        var pg = com.alibaba.druid.sql.SQLUtils.parseStatements(
                "CREATE INDEX idx_a ON t (a)", com.alibaba.druid.DbType.postgresql).get(0);
        assertEquals("DROP INDEX idx_a",
                ddlMapper.map(pg, com.sqlaudit.common.enums.Dialect.PG).orElseThrow().reverseSql());
    }

    @Test
    void ddlRename_reverseRename() {
        var sql = com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement("RENAME TABLE a TO b");
        var reverse = ddlMapper.map(sql, com.sqlaudit.common.enums.Dialect.MYSQL);
        assertTrue(reverse.isPresent());
        assertEquals("RENAME TABLE b TO a", reverse.get().reverseSql());
        assertTrue(reverse.get().precondition().contains("依赖"));
    }

    @Test
    void ddlUnknown_empty_notGuessed() {
        // 不认识的 DDL 形态返回 empty → 上层标 not_available，绝不猜测
        var sql = com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement(
                "ALTER TABLE t ADD PARTITION (PARTITION p1 VALUES LESS THAN (100))");
        assertTrue(ddlMapper.map(sql, com.sqlaudit.common.enums.Dialect.MYSQL).isEmpty());
    }
}
