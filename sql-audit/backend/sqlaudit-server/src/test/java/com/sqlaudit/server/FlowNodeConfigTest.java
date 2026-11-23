package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批流配置（契约 §5 / §6 审批流配置段）：范围 × 风险等级唯一（409）、节点整体替换与
 * 保存期校验（组内 mode 一致 / node_order 从 1 连续 / ROLE 有有效成员 / USER 有效 /
 * INSTANCE_OWNER ref 恒 *）、变更后 reload 生效。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class FlowNodeConfigTest extends MySqlItBase {

    private String adminToken;
    private long adminId;
    private long instanceId;
    private long flowId;

    @BeforeEach
    void setUpFixtures() throws Exception {
        adminToken = adminToken();
        adminId = userIdOf("admin", adminToken);
        instanceId = createInstance(adminToken, "flow-target");
        String body = perform(jsonPost("/api/config/flows", adminToken,
                "{\"name\":\"实例流 · HIGH\",\"instanceId\":" + instanceId
                        + ",\"riskLevel\":\"HIGH\",\"enabled\":true}"), 200);
        flowId = json.readTree(body).path("id").asLong();
    }

    @Test
    void flowsList_showsGlobalAndInstanceFlows() throws Exception {
        JsonNode flows = json.readTree(getJson("/api/config/flows", adminToken));
        assertEquals(5, flows.size(), "4 条全局种子 + 1 条实例流: " + flows);
        boolean sawInstance = false;
        for (JsonNode f : flows) {
            if (f.path("id").asLong() == flowId) {
                sawInstance = true;
                assertEquals("flow-target", f.path("instanceName").asText());
                assertEquals("HIGH", f.path("riskLevel").asText());
            }
        }
        assertTrue(sawInstance, "应看到实例流及其 instanceName");
    }

    @Test
    void duplicateScopeAndRisk_409() throws Exception {
        String body = perform(jsonPost("/api/config/flows", adminToken,
                "{\"name\":\"重复\",\"instanceId\":" + instanceId + ",\"riskLevel\":\"HIGH\"}"), 409);
        expectCode(body, "DUPLICATE_FLOW");

        // 全局 LOW 种子已存在 → 同样 409
        String body2 = perform(jsonPost("/api/config/flows", adminToken,
                "{\"name\":\"重复\",\"riskLevel\":\"LOW\"}"), 409);
        expectCode(body2, "DUPLICATE_FLOW");
    }

    @Test
    void replaceNodes_validRoundTrip() throws Exception {
        mvc.perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                        "[{\"nodeOrder\":1,\"approverType\":\"ROLE\",\"approverRef\":\"LEAD\",\"mode\":\"OR\"},"
                                + "{\"nodeOrder\":2,\"approverType\":\"USER\",\"approverRef\":\"" + adminId
                                + "\",\"mode\":\"AND\"}]"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/config/flows/" + flowId + "/nodes")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeOrder").value(1))
                .andExpect(jsonPath("$[0].approverType").value("ROLE"))
                .andExpect(jsonPath("$[0].approverRef").value("LEAD"))
                .andExpect(jsonPath("$[0].approverDisplay").value("主管"))
                .andExpect(jsonPath("$[0].mode").value("OR"))
                .andExpect(jsonPath("$[1].nodeOrder").value(2))
                .andExpect(jsonPath("$[1].approverType").value("USER"))
                .andExpect(jsonPath("$[1].approverDisplay").value("超级管理员"));
    }

    @Test
    void replaceNodes_instanceOwnerRoundTrip() throws Exception {
        mvc.perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                        "[{\"nodeOrder\":1,\"approverType\":\"INSTANCE_OWNER\",\"approverRef\":\"*\",\"mode\":\"OR\"}]"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/config/flows/" + flowId + "/nodes")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[0].approverType").value("INSTANCE_OWNER"))
                .andExpect(jsonPath("$[0].approverDisplay").value("实例负责人"));
    }

    @Test
    void replaceNodes_empty_isFreePass() throws Exception {
        mvc.perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken, "[]"))
                .andExpect(status().isOk());
        assertEquals(0, json.readTree(getJson("/api/config/flows/" + flowId + "/nodes", adminToken)).size());
    }

    @Test
    void replaceNodes_groupModeMismatch_400() throws Exception {
        String body = perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                "[{\"nodeOrder\":1,\"approverType\":\"ROLE\",\"approverRef\":\"LEAD\",\"mode\":\"OR\"},"
                        + "{\"nodeOrder\":1,\"approverType\":\"ROLE\",\"approverRef\":\"DBA\",\"mode\":\"AND\"}]"), 400);
        expectCode(body, "FLOW_NODE_INVALID");
    }

    @Test
    void replaceNodes_nonContiguousOrder_400() throws Exception {
        String body = perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                "[{\"nodeOrder\":2,\"approverType\":\"ROLE\",\"approverRef\":\"LEAD\",\"mode\":\"OR\"}]"), 400);
        expectCode(body, "FLOW_NODE_INVALID");
    }

    @Test
    void replaceNodes_unknownRole_400() throws Exception {
        String body = perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                "[{\"nodeOrder\":1,\"approverType\":\"ROLE\",\"approverRef\":\"NO_SUCH_ROLE\",\"mode\":\"OR\"}]"), 400);
        expectCode(body, "FLOW_NODE_INVALID");
    }

    @Test
    void replaceNodes_disabledUser_400() throws Exception {
        long devId = userIdOf("dev", adminToken);
        jdbc.update("UPDATE t_user SET status='DISABLED' WHERE id=?", devId);
        String body = perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                "[{\"nodeOrder\":1,\"approverType\":\"USER\",\"approverRef\":\"" + devId + "\",\"mode\":\"OR\"}]"), 400);
        expectCode(body, "FLOW_NODE_INVALID");
    }

    @Test
    void replaceNodes_badInstanceOwnerRef_400() throws Exception {
        String body = perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                "[{\"nodeOrder\":1,\"approverType\":\"INSTANCE_OWNER\",\"approverRef\":\"12\",\"mode\":\"OR\"}]"), 400);
        expectCode(body, "FLOW_NODE_INVALID");
    }

    @Test
    void updateFlow_nameAndEnabled_only() throws Exception {
        mvc.perform(jsonPut("/api/config/flows/" + flowId, adminToken,
                        "{\"name\":\"改名\",\"enabled\":false}"))
                .andExpect(status().isOk());
        JsonNode flows = json.readTree(getJson("/api/config/flows", adminToken));
        for (JsonNode f : flows) {
            if (f.path("id").asLong() == flowId) {
                assertEquals("改名", f.path("name").asText());
                assertEquals(false, f.path("enabled").asBoolean());
            }
        }
    }

    @Test
    void deleteFlow_removesWithNodes() throws Exception {
        mvc.perform(jsonPut("/api/config/flows/" + flowId + "/nodes", adminToken,
                        "[{\"nodeOrder\":1,\"approverType\":\"ROLE\",\"approverRef\":\"LEAD\",\"mode\":\"OR\"}]"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/config/flows/" + flowId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_flow_node WHERE flow_rule_id=?",
                Integer.class, flowId));
        assertEquals(4, json.readTree(getJson("/api/config/flows", adminToken)).size());
    }

    // ---------------- helper ----------------

    private long createInstance(String token, String name) throws Exception {
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"ownerId\":" + userIdOf("admin", token)
                        + ",\"host\":\"127.0.0.1\",\"port\":3306,\"defaultSchema\":\"nope\",\"status\":\"ACTIVE\"}"),
                200);
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
