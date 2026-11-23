package com.sqlaudit.server.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.server.auth.CurrentUserService;
import com.sqlaudit.server.auth.PermissionService;
import com.sqlaudit.server.config.FlowConfigLoader;
import com.sqlaudit.server.test.ServerItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端集成测试基类（MySQL 独立库 sql_audit_it，profile {@code it}，执行循环关闭）。
 *
 * <p>子类需自行标注 {@code @EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")}
 * （JUnit 条件注解须在具体测试类上，且其求值先于 Spring 上下文加载——MySQL 不可用时
 * 整类跳过而不是上下文启动失败）。每个测试方法前清库重放种子，并同步失效服务端缓存
 * （权限快照 / 当前用户缓存）与重载审批流配置（契约 §4.1 测试约定）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "mysql"})
public abstract class MySqlItBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected PermissionService permissionService;

    @Autowired
    protected CurrentUserService currentUserService;

    @Autowired
    protected FlowConfigLoader flowConfigLoader;

    @BeforeEach
    void resetDbAndCaches() {
        ServerItSupport.cleanAndSeed(jdbc);
        permissionService.reload();
        currentUserService.invalidateAll();
        flowConfigLoader.reload();
    }

    // ---------------- 登录与请求 helper ----------------

    /** 登录并返回 token。 */
    protected String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(bodyOf(result));
        return node.get("token").asText();
    }

    /** 准备生产目标库表（t_demo 于 sql_audit_it_target），提交 / 执行类用例的元数据源。 */
    protected void ensureTarget() {
        ServerItSupport.ensureTargetTable();
    }

    /** 经 API 创建指向 sql_audit_it_target 的「生产实例」（含 RO_META/RW_EXEC 凭据）。 */
    protected long createTargetInstance(String token, String name) throws Exception {
        return createInstance(token, name, "sql_audit_it_target", true);
    }

    /** 经 API 创建实例（无凭据、元数据不可用——数据范围 / 越权类用例用）。 */
    protected long createBareInstance(String token, String name) throws Exception {
        return createInstance(token, name, "nope", false);
    }

    private long createInstance(String token, String name, String schema, boolean withCredential)
            throws Exception {
        long adminId = adminId(token);
        String credential = withCredential
                ? ",\"username\":\"root\",\"password\":\"password\""
                : "";
        String body = perform(jsonPost("/api/config/instances", token,
                "{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"ownerId\":" + adminId
                        + ",\"host\":\"127.0.0.1\",\"port\":3306,\"defaultSchema\":\"" + schema
                        + "\",\"status\":\"ACTIVE\"" + credential + "}"), 200);
        return json.readTree(body).path("id").asLong();
    }

    /** 找 admin 的用户 id（种子不写死自增 id）。 */
    protected long adminId(String token) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", token))) {
            if ("admin".equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到 admin 用户");
    }

    protected String adminToken() throws Exception {
        return login("admin", "admin12345");
    }

    protected String devToken() throws Exception {
        return login("dev", "dev12345");
    }

    /** GET + Bearer token，返回响应体文本（UTF-8 解码）。 */
    protected String getJson(String path, String token) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return bodyOf(result);
    }

    /** 响应体按 UTF-8 解码（MockHttpServletResponse 默认 ISO-8859-1，中文会乱码）。 */
    protected static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 响应体文本 → JSON 树。 */
    protected JsonNode tree(String body) throws Exception {
        return json.readTree(body);
    }

    /** 校验错误响应体的 code 字段。 */
    protected static void expectCode(String body, String code) throws Exception {
        JsonNode node = new ObjectMapper().readTree(body);
        if (!code.equals(node.path("code").asText())) {
            throw new AssertionError("期望错误码 " + code + "，实际 " + body);
        }
    }

    /** 执行请求并校验 HTTP 状态（失败时断言消息带上响应体，返回 body）。 */
    protected String perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                             int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(req).andReturn();
        int actual = result.getResponse().getStatus();
        if (actual != expectedStatus) {
            throw new AssertionError("期望 HTTP " + expectedStatus + " 实际 " + actual
                    + " body=" + bodyOf(result));
        }
        return bodyOf(result);
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder jsonPost(
            String path, String token, String body) {
        return post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder jsonPut(
            String path, String token, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // ---------------- 种子口径断言 helper ----------------

    /** admin 的 /api/auth/me 应返回 UserView（含 apis 能力清单）。 */
    protected void assertAdminMeShape(String token) throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.displayName").isNotEmpty())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.apis").isArray());
    }
}
