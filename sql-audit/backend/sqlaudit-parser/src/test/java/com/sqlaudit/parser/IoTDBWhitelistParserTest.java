package com.sqlaudit.parser;

import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.iotdb.IoTDBWhitelistParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IoTDB 语句白名单解析测试（README §4.3 / README §4.4：MVP 不做 AST，白名单外一律高危）。
 */
class IoTDBWhitelistParserTest {

    private final IoTDBWhitelistParser parser = new IoTDBWhitelistParser();

    @Test
    void select_isReadOnly() {
        ParsedStatement ps = parser.parse("SELECT * FROM root.sg.device1").get(0);
        assertTrue(ps.isReadOnly());
        assertEquals(StatementType.SELECT, ps.getStatementType());
        assertEquals("root.sg.device1", ps.getObjects().get(0).getName());
    }

    @Test
    void insert_notReadOnly_withPath() {
        ParsedStatement ps = parser.parse(
                "INSERT INTO root.sg.d1(timestamp, status) VALUES (2026-01-01T00:00:00, true)").get(0);
        assertEquals(StatementType.INSERT, ps.getStatementType());
        assertFalse(ps.isReadOnly());
        assertEquals("root.sg.d1", ps.getObjects().get(0).getName());
    }

    @Test
    void delete_notReadOnly() {
        ParsedStatement ps = parser.parse(
                "DELETE FROM root.sg.d1 WHERE time <= 2026-01-01T00:00:00").get(0);
        assertEquals(StatementType.DELETE, ps.getStatementType());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void createTimeseries_isDdl() {
        ParsedStatement ps = parser.parse(
                "CREATE TIMESERIES root.sg.d1.status WITH DATATYPE=BOOLEAN, ENCODING=PLAIN").get(0);
        assertEquals(StatementType.DDL, ps.getStatementType());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void dropTimeseries_isDdl() {
        ParsedStatement ps = parser.parse("DROP TIMESERIES root.sg.d1.status").get(0);
        assertEquals(StatementType.DDL, ps.getStatementType());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void ttlOutsideWhitelist_lowConfidence() {
        // TTL / 模板变更白名单外 → 高危人工审
        ParsedStatement ps = parser.parse("SET TTL TO root.sg 3600").get(0);
        assertEquals(0, ps.getParseConfidence());
        assertFalse(ps.isReadOnly());
    }

    @Test
    void multiStatementSplit() {
        List<ParsedStatement> list = parser.parse(
                "SELECT * FROM root.sg.**; INSERT INTO root.sg.d1(timestamp, status) VALUES (1, true)");
        assertEquals(2, list.size());
        assertTrue(list.get(0).isReadOnly());
        assertFalse(list.get(1).isReadOnly());
    }
}
