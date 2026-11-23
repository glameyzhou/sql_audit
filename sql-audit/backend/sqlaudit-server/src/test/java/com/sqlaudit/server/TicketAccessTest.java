package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单对象级访问（契约 §4.5）：提交人可见；审批链点名 / 决策过的人可见；「查看全部工单」
 * 能力者可见；其余 403 TICKET_ACCESS_DENIED。取消仅提交人。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class TicketAccessTest extends MySqlItBase {

    private long instanceId;
    private long ticketId;
    private String devToken;
    private String adminToken;
    private String otherToken;
    private String leadToken;

    @BeforeEach
    void setUpFixtures() throws Exception {
        adminToken = adminToken();
        devToken = devToken();
        ensureTarget();
        instanceId = createTargetInstance(adminToken, "access-target");

        // dev 提交一张 MEDIUM 工单（UPDATE 带 WHERE，无规则命中 → MEDIUM）
        String submitResp = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"访问控制工单\",\"instanceId\":" + instanceId
                        + ",\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\"}"), 200);
        ticketId = json.readTree(submitResp).path("ticketId").asLong();

        // other1：仅 DEV 角色（提交 + 查询，无 ticket.view_all）
        long otherId = createUser(adminToken, "other1", "other12345");
        perform(jsonPut("/api/users/" + otherId + "/roles", adminToken, "{\"roles\":[\"DEV\"]}"), 200);
        otherToken = login("other1", "other12345");

        // lead2：仅 LEAD 角色（无 ticket.view_all；可见性来自「被审批链点名」）
        long leadId = createUser(adminToken, "lead2", "lead12345");
        perform(jsonPut("/api/users/" + leadId + "/roles", adminToken, "{\"roles\":[\"LEAD\"]}"), 200);
        leadToken = login("lead2", "lead12345");
    }

    @Test
    void creator_seesOwnTicket() throws Exception {
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creatorName").value("开发"))
                .andExpect(jsonPath("$.instanceName").value("access-target"))
                .andExpect(jsonPath("$.status").value("auditing"))
                .andExpect(jsonPath("$.sqlText").value(
                        org.hamcrest.Matchers.containsString("UPDATE t_demo")));
    }

    @Test
    void unrelatedUser_403() throws Exception {
        String body = perform(get("/api/tickets/" + ticketId)
                .header("Authorization", "Bearer " + otherToken), 403);
        expectCode(body, "TICKET_ACCESS_DENIED");
        String execBody = perform(get("/api/executions/ticket/" + ticketId)
                .header("Authorization", "Bearer " + otherToken), 403);
        expectCode(execBody, "TICKET_ACCESS_DENIED");
    }

    @Test
    void adminWithViewAll_seesAnyTicket() throws Exception {
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void approverNamedByChain_seesTicket() throws Exception {
        // 提交即生成审批节点（两步合并）→ MEDIUM 链 = ROLE LEAD 已落库 → lead2 被点名，可见
        mvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + leadToken))
                .andExpect(status().isOk());
    }

    @Test
    void cancel_creatorOnly() throws Exception {
        String cancelBody = perform(jsonPost("/api/tickets/" + ticketId + "/cancel", otherToken, "{}"), 403);
        expectCode(cancelBody, "TICKET_ACCESS_DENIED");

        // 提交人取消成功
        mvc.perform(jsonPost("/api/tickets/" + ticketId + "/cancel", devToken, "{}"))
                .andExpect(status().isOk());
    }

    @Test
    void listMine_onlyOwnTickets() throws Exception {
        // 分页响应 {items,total}
        JsonNode devTickets = json.readTree(getJson("/api/tickets", devToken));
        assertEquals(1, devTickets.path("items").size());
        assertEquals(1, devTickets.path("total").asLong());
        assertEquals("访问控制工单", devTickets.path("items").get(0).path("title").asText());

        JsonNode otherTickets = json.readTree(getJson("/api/tickets", otherToken));
        assertEquals(0, otherTickets.path("items").size());
        assertEquals(0, otherTickets.path("total").asLong());
    }

    // ---------------- helper ----------------

    private long createUser(String token, String username, String password) throws Exception {
        String body = perform(jsonPost("/api/users", token,
                "{\"username\":\"" + username + "\",\"displayName\":\"" + username
                        + "\",\"password\":\"" + password + "\"}"), 200);
        return json.readTree(body).path("id").asLong();
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
