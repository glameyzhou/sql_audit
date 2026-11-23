package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.query.channel.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交 → 审批端到端集成测试（内存 fake DAO + H2 查询演示库）。
 *
 * <p>生产路径的查询连接已改为按实例真连（{@code JdbcQueryConnectionProvider}），
 * 集成测试用 {@link DemoQueryConnection} 覆盖为 H2 演示库，保证用例不依赖外部 MySQL。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SubmissionFlowTest.DemoQueryConnection.class)
class SubmissionFlowTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 测试专用：所有 instanceId 路由到同一个 H2 内存库（t_user 20 行，供 LIMIT 注入用例）。 */
    @TestConfiguration
    static class DemoQueryConnection {

        @Bean
        @Primary
        ConnectionProvider demoConnectionProvider() {
            return new ConnectionProvider() {
                private volatile boolean initialized;

                @Override
                public Connection getConnection(long instanceId) throws SQLException {
                    Connection conn = DriverManager.getConnection(
                            "jdbc:h2:mem:querydemo;DB_CLOSE_DELAY=-1", "sa", "");
                    if (!initialized) {
                        initDemo(conn);
                        initialized = true;
                    }
                    return conn;
                }

                private void initDemo(Connection conn) throws SQLException {
                    try (var st = conn.createStatement()) {
                        st.execute("CREATE TABLE IF NOT EXISTS t_user (id INT PRIMARY KEY, name VARCHAR(64))");
                        for (int i = 1; i <= 20; i++) {
                            st.execute("MERGE INTO t_user (id, name) KEY (id) VALUES (" + i + ", 'user" + i + "')");
                        }
                    }
                }
            };
        }
    }

    // ---------------- 预览 ----------------

    @Test
    void preview_goodUpdate_fullRollbackMediumRisk() throws Exception {
        mvc.perform(post("/api/tickets/preview")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sql":"UPDATE t_user SET a = 1 WHERE id = 1",
                                 "dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitCount").value(1))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.errorBlocked").value(false))
                .andExpect(jsonPath("$.units[0].rollbackPlan.coverage").value("FULL"));
    }

    @Test
    void preview_destructiveDdl_criticalAndSnapshot() throws Exception {
        mvc.perform(post("/api/tickets/preview")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sql":"DROP TABLE t_user",
                                 "dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.units[0].rollbackPlan.coverage").value("NONE"))
                .andExpect(jsonPath("$.units[0].rollbackPlan.generator").value("SNAPSHOT"));
    }

    // ---------------- 建单（ERROR 阻断） ----------------

    @Test
    void submit_errorBlocked_rejectedWithoutTicket() throws Exception {
        mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"无 WHERE 的 UPDATE","creatorId":100,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"UPDATE t_user SET a = 1","dialect":"MYSQL"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERROR_RULE_BLOCKED"));
    }

    // ---------------- 端到端：建单 → 提审批 → 审批通过 ----------------

    @Test
    void endToEnd_createApprove_auditChainIntact() throws Exception {
        // 1. 建单（creator=admin 即 1 号账号；审批须由非提交人完成）
        String adminToken = login("admin", "admin12345");
        MvcResult created = mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"常规变更","creatorId":1,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"UPDATE t_user SET a = 1 WHERE id = 1","dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andReturn();
        long ticketId = readTicketId(created);

        // 2. 影响面未完成 → 拒绝提交审批（§4.4 硬性要求）
        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPACT_ASSESSMENT_INCOMPLETE"));

        // 3. 影响面完成 → 提交审批（MEDIUM → LEAD 单节点链）
        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":true}"))
                .andExpect(status().isOk());

        // 4. 防自审：creator 审自己 → 拒绝（§3.4；审批人身份取 JWT）
        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approve\":true,\"comment\":\"自己批自己\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));

        // 5. 非提交人主管（LEAD）审批通过 → 工单 approved
        createLeadUser(adminToken, "lead_e2e");
        String leadToken = login("lead_e2e", "lead12345");
        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .header("Authorization", "Bearer " + leadToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approve\":true,\"comment\":\"同意\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tickets/{id}", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        // 6. 审计链完整（状态迁移 + 审批决策都进链）
        mvc.perform(get("/api/audit-logs/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // ---------------- 查询通道 ----------------

    @Test
    void query_injectsLimitAndAudits() throws Exception {
        // 无 LIMIT 的 SELECT → 平台注入 LIMIT 1000；20 行 demo 数据全返回，不截断
        mvc.perform(post("/api/query")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"instanceId":1,"sql":"SELECT * FROM t_user",
                                 "dialect":"MYSQL","userId":100}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rowCount").value(20))
                .andExpect(jsonPath("$.injected").value(true));

        // 查询进审计链
        mvc.perform(get("/api/audit-logs/verify"))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void query_updateRejectedByGate() throws Exception {
        mvc.perform(post("/api/query")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"instanceId":1,"sql":"UPDATE t_user SET a = 1 WHERE id = 1",
                                 "dialect":"MYSQL","userId":100}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    private long readTicketId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"ticketId\":");
        assertTrue(idx > 0, "响应应含 ticketId: " + body);
        String rest = body.substring(idx + 11);
        int end = rest.indexOf('}');
        return Long.parseLong(rest.substring(0, end).trim());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    /** 以 admin 身份创建一个 LEAD 角色用户（审批测试用）。 */
    private void createLeadUser(String adminToken, String username) throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"主管\","
                                + "\"password\":\"lead12345\",\"roles\":[\"LEAD\"]}"))
                .andExpect(status().isOk());
    }
}
