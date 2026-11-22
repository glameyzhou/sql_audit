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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手工用户管理集成测试（memory profile：种子 admin/dev 本地账号）。
 *
 * <p>每个用例使用独立用户名，避免共享内存存储下的跨用例顺序干扰。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAdminTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void admin_createUser_200() throws Exception {
        String token = login("admin", "admin12345");
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"爱丽丝\","
                                + "\"password\":\"alice12345\",\"roles\":[\"DEV\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void dev_accessUsers_403() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateUsername_409() throws Exception {
        String token = login("admin", "admin12345");
        String body = "{\"username\":\"dupuser\",\"displayName\":\"重复\","
                + "\"password\":\"dup12345\",\"roles\":[\"DEV\"]}";
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void createdUser_canLogin() throws Exception {
        String token = login("admin", "admin12345");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"displayName\":\"鲍勃\","
                                + "\"password\":\"bob12345\",\"roles\":[\"DEV\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("bob"));
    }

    @Test
    void resetPassword_newPasswordCanLogin() throws Exception {
        String token = login("admin", "admin12345");
        long id = createUser(token, "carol", "carol12345");
        mvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
        // 旧密码失效
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"carol12345\"}"))
                .andExpect(status().isUnauthorized());
        // 新密码可登录
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void assignRoles_takesEffect() throws Exception {
        String token = login("admin", "admin12345");
        long id = createUser(token, "dave", "dave12345");
        mvc.perform(put("/api/users/" + id + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"DBA\"]}"))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dave\",\"password\":\"dave12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(
                        node.get("user").get("roles").toString())
                .contains("DBA");
    }

    // ---------------- 工具 ----------------

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private long createUser(String token, String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\""
                                + username + "\",\"password\":\"" + password
                                + "\",\"roles\":[\"DEV\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
