package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实例管理（契约 §6 实例段）：无环境维度、负责人必填、name 唯一、编辑回填账号、软删。
 * 管理端（instance.manage）不受数据范围限制。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class ConfigInstanceTest extends MySqlItBase {

    @Test
    void createInstance_withoutOwner_400() throws Exception {
        String token = adminToken();
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"no-owner\",\"dialect\":\"MYSQL\",\"host\":\"127.0.0.1\",\"port\":3306}"), 400);
        expectCode(body, "INVALID_REQUEST");
    }

    @Test
    void createInstance_ownerNameInList() throws Exception {
        String token = adminToken();
        long id = createInstance(token, "inst-1");
        JsonNode list = json.readTree(getJson("/api/config/instances", token));
        for (JsonNode i : list) {
            if (i.path("id").asLong() == id) {
                assertEquals("inst-1", i.path("name").asText());
                assertEquals("超级管理员", i.path("ownerName").asText());
                assertEquals("MYSQL", i.path("dialect").asText());
                assertEquals("ACTIVE", i.path("status").asText());
            }
        }
    }

    @Test
    void duplicateName_400() throws Exception {
        String token = adminToken();
        createInstance(token, "dup-name");
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"dup-name\",\"dialect\":\"MYSQL\",\"ownerId\":" + userIdOf("admin", token)
                        + ",\"host\":\"127.0.0.1\",\"port\":3306}"), 400);
        expectCode(body, "BUSINESS_ERROR");
    }

    @Test
    void updateInstance_requiresOwner() throws Exception {
        String token = adminToken();
        long id = createInstance(token, "inst-2");
        String body = perform(jsonPut("/api/config/instances/" + id, token,
                "{\"name\":\"inst-2\",\"dialect\":\"MYSQL\",\"host\":\"127.0.0.1\",\"port\":3306}"), 400);
        expectCode(body, "INVALID_REQUEST");
    }

    @Test
    void updateInstance_fullRoundTrip() throws Exception {
        String token = adminToken();
        long id = createInstance(token, "inst-3");
        long devId = userIdOf("dev", token);
        mvc.perform(jsonPut("/api/config/instances/" + id, token,
                        "{\"name\":\"inst-3-renamed\",\"dialect\":\"PG\",\"ownerId\":" + devId
                                + ",\"host\":\"127.0.0.1\",\"port\":5432,\"defaultSchema\":\"demo\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        JsonNode list = json.readTree(getJson("/api/config/instances", token));
        for (JsonNode i : list) {
            if (i.path("id").asLong() == id) {
                assertEquals("inst-3-renamed", i.path("name").asText());
                assertEquals("PG", i.path("dialect").asText());
                assertEquals("开发", i.path("ownerName").asText());
                assertEquals("DISABLED", i.path("status").asText());
            }
        }
    }

    @Test
    void credentials_endpoint_returnsNoSecrets() throws Exception {
        String token = adminToken();
        long id = createInstanceWithCredential(token, "inst-4");
        mvc.perform(get("/api/config/instances/" + id + "/credentials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountType").value("RO_META"))
                .andExpect(jsonPath("$[0].username").value("root"))
                .andExpect(jsonPath("$[0].passwordEnc").doesNotExist());
    }

    @Test
    void deleteInstance_softDeletes() throws Exception {
        String token = adminToken();
        long id = createInstance(token, "inst-5");
        mvc.perform(delete("/api/config/instances/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        boolean stillThere = false;
        for (JsonNode i : json.readTree(getJson("/api/config/instances", token))) {
            if (i.path("id").asLong() == id) {
                stillThere = true;
            }
        }
        assertTrue(!stillThere, "软删后列表不应出现");
    }

    @Test
    void dev_hasNoInstanceManagePermission() throws Exception {
        mvc.perform(get("/api/config/instances").header("Authorization", "Bearer " + devToken()))
                .andExpect(status().isForbidden());
    }

    // ---------------- helper ----------------

    private long createInstance(String token, String name) throws Exception {
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"ownerId\":" + userIdOf("admin", token)
                        + ",\"host\":\"127.0.0.1\",\"port\":3306,\"defaultSchema\":\"nope\",\"status\":\"ACTIVE\"}"),
                200);
        return json.readTree(body).path("id").asLong();
    }

    private long createInstanceWithCredential(String token, String name) throws Exception {
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"ownerId\":" + userIdOf("admin", token)
                        + ",\"host\":\"127.0.0.1\",\"port\":3306,\"defaultSchema\":\"nope\",\"status\":\"ACTIVE\","
                        + "\"username\":\"root\",\"password\":\"password\"}"), 200);
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
