package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统配置 CRUD 集成测试（memory profile：种子 admin/dev 本地账号）。
 *
 * <p>覆盖：admin 建实例 200 / dev 建实例 403 / 建后列表可见 / 更新生效 / 删除后消失 /
 * 写操作产生审计记录。各用例用唯一实例名隔离，互不影响。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigCrudTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void admin_createInstance_200_and_visibleInList() throws Exception {
        String token = login("admin", "admin12345");
        String name = uniqueName("inst");

        mvc.perform(post("/api/config/instances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJson(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());

        assertThat(instanceExists(token, name)).isTrue();
    }

    @Test
    void dev_createInstance_403() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(post("/api/config/instances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJson(uniqueName("inst"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateInstance_takesEffect() throws Exception {
        String token = login("admin", "admin12345");
        String name = uniqueName("inst");
        long id = createInstance(token, name);

        mvc.perform(put("/api/config/instances/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJson("updated-" + name)))
                .andExpect(status().isOk());

        assertThat(instanceExists(token, "updated-" + name)).isTrue();
    }

    @Test
    void deleteInstance_disappearsFromList() throws Exception {
        String token = login("admin", "admin12345");
        String name = uniqueName("inst");
        long id = createInstance(token, name);

        mvc.perform(delete("/api/config/instances/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(instanceExists(token, name)).isFalse();
    }

    @Test
    void writeOperations_produceAuditRecords() throws Exception {
        String token = login("admin", "admin12345");
        String name = uniqueName("inst");
        long id = createInstance(token, name);
        mvc.perform(put("/api/config/instances/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJson("updated-" + name)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/config/instances/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/audit-logs")
                        .param("fromSeq", "1").param("toSeq", "1000"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("INSTANCE_CREATE", "INSTANCE_UPDATE", "INSTANCE_DELETE");
    }

    @Test
    void createInstance_withCredentials_persistsRoMetaAndRwExec_withoutEchoingPassword() throws Exception {
        String token = login("admin", "admin12345");
        String name = uniqueName("inst");

        MvcResult created = mvc.perform(post("/api/config/instances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJsonWithCredentials(name, "app_user", "s3cret")))
                .andExpect(status().isOk())
                .andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        MvcResult creds = mvc.perform(get("/api/config/instances/" + id + "/credentials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = mapper.readTree(creds.getResponse().getContentAsString());

        // 一组账号 → 两条凭据（RO_META + RW_EXEC），账号一致
        assertThat(arr).hasSize(2);
        assertThat(arr).allSatisfy(node -> {
            assertThat(node.path("username").asText()).isEqualTo("app_user");
            assertThat(node.has("password")).isFalse();
            assertThat(node.has("passwordEnc")).isFalse();
        });
        assertThat(arr.get(0).path("accountType").asText()).isEqualTo("RO_META");
        assertThat(arr.get(1).path("accountType").asText()).isEqualTo("RW_EXEC");
    }

    // ---------------- 工具 ----------------

    private long createInstance(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/config/instances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instanceJson(name)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private boolean instanceExists(String token, String name) throws Exception {
        MvcResult result = mvc.perform(get("/api/config/instances")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode node : arr) {
            if (name.equals(node.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    private static String instanceJson(String name) {
        return "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"environment\":\"prod\","
                + "\"ownerId\":1,\"host\":\"127.0.0.1\",\"port\":3306,"
                + "\"defaultSchema\":\"db1\",\"status\":\"ACTIVE\"}";
    }

    private static String instanceJsonWithCredentials(String name, String username, String password) {
        return "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"environment\":\"test\","
                + "\"ownerId\":1,\"host\":\"127.0.0.1\",\"port\":3306,"
                + "\"defaultSchema\":\"db1\",\"status\":\"ACTIVE\","
                + "\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
