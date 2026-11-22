package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Instance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实例通用端点集成测试（memory profile：种子 admin/dev 本地账号）。
 *
 * <p>覆盖：dev 角色可见实例列表 / 未登录 401 / 连通性测试对不可达地址与 IoTDB 均为
 * ok:false 而非 500 / 不存在实例的库列表 400 / 建实例不带 ownerId 时落库为当前用户。
 * 各用例用唯一实例名隔离，互不影响。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstanceControllerTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private UserDao userDao;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dev_listInstances_200_andVisible() throws Exception {
        String adminToken = login("admin", "admin12345");
        String name = uniqueName("inst");
        createInstance(adminToken, name, false);

        String devToken = login("dev", "dev12345");
        MvcResult result = mvc.perform(get("/api/instances")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(instanceVisible(result, name)).isTrue();
    }

    @Test
    void listInstances_withoutToken_401() throws Exception {
        mvc.perform(get("/api/instances"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testConnection_unreachableHost_okFalse_not500() throws Exception {
        String token = login("dev", "dev12345");
        // 127.0.0.1:1 无监听，立即连接拒绝；期望 200 + ok:false 而非 500
        mvc.perform(post("/api/instances/test-connection")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dialect\":\"MYSQL\",\"host\":\"127.0.0.1\",\"port\":1,"
                                + "\"username\":\"u\",\"password\":\"p\",\"database\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testConnection_iotdb_okFalse() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(post("/api/instances/test-connection")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dialect\":\"IOTDB\",\"host\":\"127.0.0.1\",\"port\":6667,"
                                + "\"username\":\"u\",\"password\":\"p\",\"database\":\"root.db\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("暂不支持")));
    }

    @Test
    void databases_instanceMissing_400() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(get("/api/instances/99999999/databases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_FOUND"));
    }

    @Test
    void createInstance_withoutOwnerId_ownerIsCurrentUser() throws Exception {
        String token = login("admin", "admin12345");
        long id = createInstance(token, uniqueName("inst"), false);

        long adminId = userDao.findByUsername("admin").getId();
        Instance saved = instanceDao.findById(id);
        assertThat(saved).isNotNull();
        assertThat(saved.getOwnerId()).isEqualTo(adminId);
    }

    @Test
    void createInstance_withExplicitOwnerId_kept() throws Exception {
        String token = login("admin", "admin12345");
        long id = createInstance(token, uniqueName("inst"), true);

        Instance saved = instanceDao.findById(id);
        assertThat(saved).isNotNull();
        assertThat(saved.getOwnerId()).isEqualTo(12345L);
    }

    // ---------------- 工具 ----------------

    private long createInstance(String token, String name, boolean withOwner) throws Exception {
        String body = withOwner
                ? "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"environment\":\"prod\","
                        + "\"ownerId\":12345,\"host\":\"127.0.0.1\",\"port\":3306,"
                        + "\"defaultSchema\":\"db1\",\"status\":\"ACTIVE\"}"
                : "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"environment\":\"prod\","
                        + "\"host\":\"127.0.0.1\",\"port\":3306,"
                        + "\"defaultSchema\":\"db1\",\"status\":\"ACTIVE\"}";
        MvcResult result = mvc.perform(post("/api/config/instances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private boolean instanceVisible(MvcResult result, String name) throws Exception {
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
}
