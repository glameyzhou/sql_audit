package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 枚举取值在接口边界上的行为（memory profile）。
 *
 * <p>状态、环境、方言等字段从 String 改成枚举后，对外的三条约定在这里锁死：</p>
 * <ol>
 *   <li>JSON / 查询参数里的取值与改造前完全一致（存储码），响应体不变；</li>
 *   <li>大小写不敏感：前端按存储码传小写（{@code executing} / {@code disabled}）照常工作；</li>
 *   <li>枚举之外的取值（拼错、脏值）在入口被 400 拒绝，不再被静默写进库。</li>
 * </ol>
 *
 * <p>本类会往共享的内存存储里写用户 / 实例，跑完后丢弃上下文，避免影响其他用例。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EnumBoundaryTest {

    private static final String UNIQUE = String.valueOf(System.nanoTime() % 1_000_000);

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- 查询参数：按存储码绑定，大小写不敏感 ----------------

    @Test
    void executions_bindLowercaseAndUppercaseStatus() throws Exception {
        mvc.perform(get("/api/executions").param("status", "executing"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/executions").param("status", "EXECUTING"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/executions"))                       // 缺省 = executing
                .andExpect(status().isOk());
    }

    @Test
    void executions_rejectUnknownStatus() throws Exception {
        mvc.perform(get("/api/executions").param("status", "executing-typo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------------- 请求体：按存储码反序列化，未知取值 400 ----------------

    @Test
    void preview_acceptsLowercaseDialectAndEchoesCanonicalCode() throws Exception {
        mvc.perform(post("/api/tickets/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"UPDATE t_user SET a = 1 WHERE id = 1\",\"dialect\":\"mysql\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dialect").value("MYSQL"));
    }

    @Test
    void preview_rejectsUnknownDialect() throws Exception {
        mvc.perform(post("/api/tickets/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\",\"dialect\":\"oracle\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Dialect")));
    }

    @Test
    void userStatus_typoRejected_lowercaseNormalized_wireCodeUnchanged() throws Exception {
        String token = login("admin", "admin12345");
        String username = "enum_user_" + UNIQUE;
        long id = createUser(token, username);

        // 拼错的状态码：400，并指明是哪个字段、合法值是什么
        mvc.perform(put("/api/users/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("UserStatus")))
                .andExpect(jsonPath("$.message").value(containsString("ACTIVE")))
                .andExpect(jsonPath("$.message").value(containsString("DISABLED")));

        // 小写存储码照常接受；落库归一成枚举，响应体仍是改造前的大写存储码
        mvc.perform(put("/api/users/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"disabled\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '" + username + "')].status").value("DISABLED"))
                .andExpect(jsonPath("$[?(@.username == '" + username + "')].source").value("LOCAL"));

        // 停用生效：登录被拒
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + username + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void instance_unknownEnvironmentRejected_beforeAnythingIsStored() throws Exception {
        String token = login("admin", "admin12345");
        String name = "enum_inst_bad_" + UNIQUE;
        mvc.perform(post("/api/config/instances").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"dialect\":\"MYSQL\",\"environment\":\"qa\","
                                + "\"host\":\"127.0.0.1\",\"port\":3306}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Environment")));
        assertEqualsNoInstance(token, name);
    }

    @Test
    void instance_canonicalCodesRoundTrip() throws Exception {
        String token = login("admin", "admin12345");
        String name = "enum_inst_ok_" + UNIQUE;
        mvc.perform(post("/api/config/instances").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"dialect\":\"mysql\",\"environment\":\"prod\","
                                + "\"host\":\"127.0.0.1\",\"port\":3306,\"status\":\"active\"}"))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(get("/api/instances").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mine = null;
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            if (name.equals(node.path("name").asText())) {
                mine = node;
            }
        }
        org.assertj.core.api.Assertions.assertThat(mine).as("新建实例应出现在列表中").isNotNull();
        org.assertj.core.api.Assertions.assertThat(mine.path("dialect").asText()).isEqualTo("MYSQL");
        org.assertj.core.api.Assertions.assertThat(mine.path("environment").asText()).isEqualTo("prod");
        org.assertj.core.api.Assertions.assertThat(mine.path("status").asText()).isEqualTo("ACTIVE");
    }

    // ---------------- 工具 ----------------

    private void assertEqualsNoInstance(String token, String name) throws Exception {
        MvcResult result = mvc.perform(get("/api/instances").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<String> names = new ArrayList<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            names.add(node.path("name").asText());
        }
        org.assertj.core.api.Assertions.assertThat(names).as("非法请求不得落库").doesNotContain(name);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private long createUser(String token, String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"" + username
                                + "\",\"password\":\"" + username + "\",\"roles\":[\"DEV\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
