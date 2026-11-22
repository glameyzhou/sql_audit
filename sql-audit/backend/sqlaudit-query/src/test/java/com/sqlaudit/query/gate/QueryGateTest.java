package com.sqlaudit.query.gate;

import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询门禁测试（设计文档 §6.2：parser 只读判定 → 放行 / 拒绝）。
 */
class QueryGateTest {

    private final QueryGate gate = new QueryGate();

    @Test
    void multiStatement_rejected() {
        GateResult r = gate.check("SELECT 1; SELECT 2", Dialect.MYSQL);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("单条"));
        assertNull(r.parsedStatement());
    }

    @Test
    void update_rejected() {
        GateResult r = gate.check("UPDATE t SET a = 1 WHERE id = 2", Dialect.MYSQL);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("UPDATE"));
        assertTrue(r.reason().contains("审核工单"));
    }

    @Test
    void forUpdate_rejected() {
        GateResult r = gate.check("SELECT * FROM t WHERE id = 1 FOR UPDATE", Dialect.MYSQL);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains(DangerousClause.FOR_UPDATE_LOCK.name()));
    }

    @Test
    void explainAnalyze_rejected() {
        // EXPLAIN ANALYZE 真实执行（§4.2 第 1 行），必须非只读
        GateResult r = gate.check("EXPLAIN ANALYZE SELECT * FROM t WHERE id = 1", Dialect.PG);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains(DangerousClause.EXPLAIN_ANALYZE.name()));
    }

    @Test
    void plainSelect_allowed() {
        GateResult r = gate.check("SELECT id, name FROM t WHERE id = 1", Dialect.MYSQL);
        assertTrue(r.allowed());
        assertNull(r.reason());
        assertNotNull(r.parsedStatement());
        assertEquals(StatementType.SELECT, r.parsedStatement().getStatementType());
        assertTrue(r.parsedStatement().isReadOnly());
        assertTrue(r.limitInjectable());
    }

    @Test
    void parseFailure_rejected() {
        // 解析失败铁律（§4.1）：LOW_CONFIDENCE + 非只读 → 拒绝
        GateResult r = gate.check("SELECT * FROM", Dialect.MYSQL);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains(DangerousClause.LOW_CONFIDENCE.name()));
    }

    @Test
    void iotdbSelect_allowed_butLimitNotInjectable() {
        // IoTDB 白名单放行 SELECT，但无 Druid AST → 注入层不可用，走兜底
        GateResult r = gate.check("SELECT s1 FROM root.sg.d1", Dialect.IOTDB);
        assertTrue(r.allowed());
        assertFalse(r.limitInjectable());
    }
}
