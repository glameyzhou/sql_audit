package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交 → 审批端到端（契约 §6 工单 / 审批段）：单实例提交、提交即生成审批节点（两步合并）、
 * 待审列表只显示当前组 / 本人可审 / 非本人提交、审批决策、LOW 提交即免审通过。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class SubmissionApprovalTest extends MySqlItBase {

    private String devToken;
    private String adminToken;
    private long instanceId;

    @BeforeEach
    void setUpFixtures() throws Exception {
        devToken = devToken();
        adminToken = adminToken();
        ensureTarget();
        instanceId = createTargetInstance(adminToken, "submit-target");
    }

    @Test
    void mediumTicket_fullApprovalFlow() throws Exception {
        // dev 提交 MEDIUM 工单（UPDATE 带 WHERE，无规则命中）——两步合并：提交即生成审批节点
        String submitResp = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"常规变更\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\"}"), 200);
        long ticketId = json.readTree(submitResp).path("ticketId").asLong();

        // 详情：creatorName / instanceName 由服务端补全
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creatorName").value("开发"))
                .andExpect(jsonPath("$.instanceName").value("submit-target"))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("auditing"));

        // 提交后审批节点即存在：dev 的待审列表不含自己的工单；admin（持 LEAD）可见且字段齐全
        assertEquals(0, json.readTree(getJson("/api/approvals/pending", devToken)).size());
        JsonNode adminPending = json.readTree(getJson("/api/approvals/pending", adminToken));
        assertEquals(1, adminPending.size());
        JsonNode p = adminPending.get(0);
        assertEquals(ticketId, p.path("ticketId").asLong());
        assertEquals("常规变更", p.path("ticketTitle").asText());
        assertEquals("MEDIUM", p.path("riskLevel").asText());
        assertEquals("开发", p.path("creatorName").asText());
        assertEquals(1, p.path("nodeOrder").asInt());
        assertEquals("ROLE", p.path("approverType").asText());
        assertEquals("LEAD", p.path("approverRef").asText());
        assertEquals("主管", p.path("approverDisplay").asText());
        assertEquals("OR", p.path("mode").asText());

        // admin 决策通过 → 工单 approved
        String decision = perform(jsonPost("/api/approvals/" + ticketId + "/decide", adminToken,
                "{\"approve\":true,\"comment\":\"同意\"}"), 200);
        assertTrue(json.readTree(decision).path("ticketApproved").asBoolean());
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + devToken))
                .andExpect(jsonPath("$.status").value("approved"));

        // 幂等重放：同结果重复决策 200，不报错
        perform(jsonPost("/api/approvals/" + ticketId + "/decide", adminToken,
                "{\"approve\":true}"), 200);
    }

    @Test
    void submitterCannotApproveOwnTicket() throws Exception {
        String submitResp = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"自审工单\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\"}"), 200);
        long ticketId = json.readTree(submitResp).path("ticketId").asLong();

        // 提交人直接决策 → 引擎硬校验防自审（先于节点归属校验）
        String body = perform(jsonPost("/api/approvals/" + ticketId + "/decide", devToken,
                "{\"approve\":true}"), 400);
        expectCode(body, "SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void lowRiskTicket_autoApprovedOnSubmit() throws Exception {
        String submitResp = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"只读工单\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"SELECT * FROM t_demo LIMIT 1\",\"dialect\":\"MYSQL\"}"), 200);
        long ticketId = json.readTree(submitResp).path("ticketId").asLong();

        // LOW 空链：提交即自动 approved（留痕）
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + devToken))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    void errorRuleBlocked_noTicketCreated() throws Exception {
        // UPDATE 无 WHERE → R_NO_WHERE ERROR → 400 不建单
        String body = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"危险变更\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"UPDATE t_demo SET a=1\",\"dialect\":\"MYSQL\"}"), 400);
        expectCode(body, "ERROR_RULE_BLOCKED");
        JsonNode myTickets = json.readTree(getJson("/api/tickets", devToken));
        assertEquals(0, myTickets.path("items").size(), "ERROR 命中不建单");
        assertEquals(0, myTickets.path("total").asLong());
    }

    @Test
    void oversizeTicketSql_400() throws Exception {
        StringBuilder sb = new StringBuilder("UPDATE t_demo SET b='");
        sb.append("x".repeat(1024 * 1024));
        sb.append("' WHERE id=1");
        String body = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"超长\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"" + sb + "\",\"dialect\":\"MYSQL\"}"), 400);
        expectCode(body, "SQL_TOO_LARGE");
    }

    @Test
    void oversizeQuerySql_400() throws Exception {
        String sql = "SELECT '" + "x".repeat(65 * 1024) + "'";
        String body = perform(jsonPost("/api/query", devToken,
                "{\"instanceId\":" + instanceId + ",\"sql\":\"" + sql + "\",\"dialect\":\"MYSQL\"}"), 400);
        expectCode(body, "SQL_TOO_LARGE");
    }

    // ---------------- helper ----------------

    private long userIdOf(String username, String token) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", token))) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }
}
