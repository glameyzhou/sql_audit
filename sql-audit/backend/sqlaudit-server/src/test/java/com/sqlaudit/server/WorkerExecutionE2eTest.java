package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import com.sqlaudit.server.test.ServerItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 执行循环端到端（单独打开 {@code sqlaudit.worker.enabled=true}）：
 * 提交 → 审批 → worker 自动执行 → 生产目标库数据变化 + 回滚 SQL 落库 + 库里无 SQL 明文。
 *
 * <p>生产目标库 = 独立库 {@code sql_audit_it_target}（{@link ServerItSupport#ensureTargetTable}），
 * 通过接口登记实例与凭据（RO_META / RW_EXEC），走真实元数据 / 指纹 / 执行网关。
 * 轮询等待执行完成（worker 每秒一轮，超时 60s）。</p>
 */
@SpringBootTest(properties = "sqlaudit.worker.enabled=true")
@ActiveProfiles({"it", "mysql"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class WorkerExecutionE2eTest extends MySqlItBase {

    @BeforeEach
    void prepareTarget() {
        ServerItSupport.ensureTargetTable();
    }

    @Test
    void submit_approve_workerExecutes() throws Exception {
        String adminToken = adminToken();
        String devToken = devToken();
        long adminId = userIdOf("admin", adminToken);

        // 生产实例：指向 sql_audit_it_target，root/password 凭据（RO_META + RW_EXEC）
        String instResp = perform(jsonPost("/api/config/instances", adminToken,
                "{\"name\":\"e2e-target\",\"dialect\":\"MYSQL\",\"ownerId\":" + adminId
                        + ",\"host\":\"127.0.0.1\",\"port\":3306,\"defaultSchema\":\"sql_audit_it_target\","
                        + "\"status\":\"ACTIVE\",\"username\":\"root\",\"password\":\"password\"}"), 200);
        long instanceId = json.readTree(instResp).path("id").asLong();

        // dev 提交 MEDIUM 工单（UPDATE 带 WHERE）——两步合并：提交即生成审批节点
        String submitResp = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"执行端到端\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"UPDATE t_demo SET a = 1 WHERE id = 1\",\"dialect\":\"MYSQL\"}"), 200);
        long ticketId = json.readTree(submitResp).path("ticketId").asLong();

        // admin（LEAD）直接审批通过
        perform(jsonPost("/api/approvals/" + ticketId + "/decide", adminToken,
                "{\"approve\":true}"), 200);

        // worker 执行循环自动执行：轮询直到 unit success（超时 60s）
        String status = awaitUnitStatus(ticketId);
        assertEquals("success", status, "worker 应自动执行工单 " + ticketId);

        // 生产目标库数据已被改（目标库不在平台库连接上，另开连接校验）
        try (var conn = java.sql.DriverManager.getConnection(
                ServerItSupport.TARGET_JDBC_URL, "root", "password");
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT a FROM t_demo WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        // 回滚 SQL 已生成（执行成功后 worker 落库反向语句）
        Integer rollbackCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rollback_sql WHERE unit_id = "
                        + "(SELECT id FROM t_ticket_unit WHERE ticket_id = " + ticketId + ")",
                Integer.class);
        assertEquals(1, rollbackCount);

        // 库里无 SQL 明文（README §4.7）：直查原列字节不含明文子串
        byte[] rawStatement = jdbc.queryForObject(
                "SELECT statement FROM t_ticket_unit WHERE ticket_id = " + ticketId,
                byte[].class);
        assertFalse(new String(rawStatement, StandardCharsets.UTF_8).contains("UPDATE t_demo"),
                "t_ticket_unit.statement 不应含明文");
        byte[] rawSqlText = jdbc.queryForObject(
                "SELECT sql_text FROM t_ticket WHERE id = " + ticketId, byte[].class);
        assertFalse(new String(rawSqlText, StandardCharsets.UTF_8).contains("UPDATE t_demo"),
                "t_ticket.sql_text 不应含明文");
    }

    // ---------------- helper ----------------

    private String awaitUnitStatus(long ticketId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM t_ticket_unit WHERE ticket_id = " + ticketId,
                    String.class);
            if ("success".equals(status) || "failed".equals(status) || "needs_confirm".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return jdbc.queryForObject(
                "SELECT status FROM t_ticket_unit WHERE ticket_id = " + ticketId, String.class);
    }

    private long userIdOf(String username, String token) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", token))) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }
}
