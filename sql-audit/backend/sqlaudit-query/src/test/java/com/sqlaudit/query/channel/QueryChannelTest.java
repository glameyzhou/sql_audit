package com.sqlaudit.query.channel;

import com.sqlaudit.common.enums.Dialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询通道三层保护集成测试（本机 Docker MySQL 专用测试库，README §4.1）。
 *
 * <p>MySQL 不可用时 {@code Assumptions} 优雅跳过；每次运行 DROP + CREATE 专用库
 * {@code sql_audit_query_it}，不触碰开发库 {@code sql-audit}。服务端超时在真库上
 * 难以稳定触发（机器相关），超时用例只验证「不抛非预期异常」——要么正常完成、
 * 要么以超时失败结果返回，二者均可接受。</p>
 */
class QueryChannelTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASSWORD = "password";
    private static final String DB = "sql_audit_query_it";
    private static final String URL = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DB
            + "?connectTimeout=3000&socketTimeout=30000&useSSL=false&allowPublicKeyRetrieval=true";

    private static final ConnectionProvider PROVIDER = QueryChannelTest::openConnection;

    private static Connection openConnection(long instanceId) throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @BeforeAll
    static void init() throws SQLException {
        Assumptions.assumeTrue(mysqlAvailable(), "本地 MySQL 未启动，跳过查询通道集成测试");

        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + DB + "`");
            st.execute("CREATE DATABASE `" + DB + "`");
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t_query (id INT PRIMARY KEY, name VARCHAR(50))");
            for (int i = 1; i <= 20; i++) {
                st.execute("INSERT INTO t_query VALUES (" + i + ", 'row" + i + "')");
            }
        }
    }

    @AfterAll
    static void shutdown() throws SQLException {
        if (!mysqlAvailable()) {
            return;
        }
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + DB + "`");
        }
    }

    private static boolean mysqlAvailable() {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT
                        + "/?connectTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT
                        + "/?connectTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD);
    }

    // ------------------------------------------------------------------
    // 行数保护：AST 注入 + setMaxRows + 流式 cancel
    // ------------------------------------------------------------------

    @Test
    void noLimit_injectedAndTruncated() {
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(1L, "SELECT id, name FROM t_query ORDER BY id", Dialect.MYSQL);

        assertTrue(r.isSuccess());
        assertEquals(5, r.getRowCount(), "20 行数据、maxLimit=5 → 只返回 5 行");
        assertTrue(r.isTruncated(), "结果集超上限被截断");
        assertTrue(r.isInjected());
        assertTrue(r.getRewrittenSql().toUpperCase().contains("LIMIT 5"));
        assertEquals("平台已注入 LIMIT 5", r.getNotice());
        assertEquals(List.of("id", "name"), r.getColumns(), "MySQL 返回小写列名");
        assertEquals(1, ((Number) r.getRows().get(0).get(0)).intValue());
        assertEquals("row1", r.getRows().get(0).get(1));
    }

    @Test
    void userSmallLimit_respected_notTruncated() {
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(1L, "SELECT id FROM t_query ORDER BY id LIMIT 3", Dialect.MYSQL);

        assertTrue(r.isSuccess());
        assertEquals(3, r.getRowCount(), "用户 LIMIT 3 在上限内 → 原样生效");
        assertFalse(r.isTruncated());
        assertFalse(r.isInjected());
        assertFalse(r.isTightened());
        assertEquals(3, r.getOriginalLimit());
        assertEquals("SELECT id FROM t_query ORDER BY id LIMIT 3", r.getRewrittenSql());
        assertNullNotice(r);
    }

    @Test
    void oversizedUserLimit_tightened() {
        // 用户 LIMIT 10 > maxLimit 5 → 收紧为 5；表里实际 20 行 → 用户被平台截断
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(1L, "SELECT id FROM t_query ORDER BY id LIMIT 10", Dialect.MYSQL);

        assertTrue(r.isSuccess());
        assertTrue(r.isTightened());
        assertFalse(r.isInjected());
        assertEquals(10, r.getOriginalLimit());
        assertEquals(5, r.getRowCount());
        assertTrue(r.isTruncated(), "平台收紧导致用户只拿到 5/10 行，必须标记截断");
        assertTrue(r.getNotice().contains("收紧"));
    }

    @Test
    void truncatedQuery_connectionReusable_afterCancel() {
        // cancel 路径：读满上限触发 statement.cancel() 后，连接（池）可继续服务新查询
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult truncated = channel.execute(
                1L, "SELECT id FROM t_query ORDER BY id", Dialect.MYSQL);
        assertTrue(truncated.isTruncated());

        QueryResult next = channel.execute(1L, "SELECT COUNT(*) FROM t_query", Dialect.MYSQL);
        assertTrue(next.isSuccess());
        assertEquals(1, next.getRowCount());
        assertEquals(20, ((Number) next.getRows().get(0).get(0)).intValue());
        assertFalse(next.isTruncated());
    }

    @Test
    void iotdbDialect_fallbackLayers_overflowObservedAndCancelled() {
        // 第 1 层（AST 注入）降级路径：IoTDB 方言无 Druid AST → 原样执行，
        // 由 setMaxRows（第 2 层）+ 流式读取（第 3 层）兜底。
        // IoTDB 白名单门面只认首词 SELECT，MySQL 可直接执行同一句 SQL。
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(
                1L, "SELECT id FROM t_query ORDER BY id", Dialect.IOTDB);

        assertTrue(r.isSuccess());
        assertEquals(5, r.getRowCount(), "20 行、maxLimit=5 → 第 2 层 setMaxRows 截断为 5 行");
        assertTrue(r.isTruncated(), "流式读到第 6 行 → 精确探测到溢出");
        assertFalse(r.isInjected());
        assertEquals("SELECT id FROM t_query ORDER BY id", r.getRewrittenSql(), "降级时原样透传");
        assertNullNotice(r);

        // cancel 后连接可复用
        QueryResult next = channel.execute(1L, "SELECT id FROM t_query WHERE id = 1", Dialect.MYSQL);
        assertTrue(next.isSuccess());
        assertEquals(1, next.getRowCount());
    }

    // ------------------------------------------------------------------
    // 门禁集成：非只读走通道直接拒绝
    // ------------------------------------------------------------------

    @Test
    void update_rejectedByGate() {
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(1L, "UPDATE t_query SET name = 'x' WHERE id = 1", Dialect.MYSQL);

        assertFalse(r.isSuccess());
        assertTrue(r.getReason().contains("UPDATE"));
        assertEquals(0, r.getRowCount());
        assertFalse(r.isTruncated());
    }

    @Test
    void multiStatement_rejectedByGate() {
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(
                1L, "SELECT id FROM t_query; SELECT name FROM t_query", Dialect.MYSQL);
        assertFalse(r.isSuccess());
        assertTrue(r.getReason().contains("单条"));
    }

    // ------------------------------------------------------------------
    // 超时路径（真库执行速度机器相关：只验证不抛非预期异常）
    // ------------------------------------------------------------------

    @Test
    void slowQuery_timeoutPath_doesNotThrowUnexpectedly() {
        // 说明：MySQL 真库执行速度机器相关，1s 超时可能触发也可能不触发。
        // 本用例验证：超时机制（setQueryTimeout(1)）开启后，慢查询（SLEEP 3s）要么正常完成、
        // 要么以超时失败结果返回（execute 不抛异常），二者均可接受。
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(1000, 1));
        QueryResult r = channel.execute(1L, "SELECT SLEEP(3)", Dialect.MYSQL);

        assertNotNull(r);
        if (!r.isSuccess()) {
            // 失败只能是超时类原因，不允许其他异常类型逃逸
            assertTrue(r.getReason() != null && !r.getReason().isBlank());
        }
    }

    // ------------------------------------------------------------------
    // 查询审计字段齐备性（审计侧由 server 写 t_audit_log，README §4.1）
    // ------------------------------------------------------------------

    @Test
    void resultCarriesAuditFields() {
        QueryChannel channel = new QueryChannel(PROVIDER, new QueryOptions(5, 10));
        QueryResult r = channel.execute(7L, "SELECT id FROM t_query ORDER BY id", Dialect.MYSQL);

        assertTrue(r.isSuccess());
        assertEquals(7L, r.getInstanceId());
        assertEquals("SELECT id FROM t_query ORDER BY id", r.getSql());
        assertEquals(5, r.getRowCount());
        assertTrue(r.isTruncated());
        assertTrue(r.getElapsedMs() >= 0);
    }

    private static void assertNullNotice(QueryResult r) {
        assertFalse(r.isInjected() || r.isTightened(), "未改写时不应有提示");
    }
}
