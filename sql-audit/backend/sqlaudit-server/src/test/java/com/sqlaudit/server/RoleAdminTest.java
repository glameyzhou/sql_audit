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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理集成测试（memory profile）：自定义角色 CRUD + 内置角色保护。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleAdminTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void admin_listRoles_returnsBuiltInFour() throws Exception {
        String token = login("admin", "admin12345");
        MvcResult result = mvc.perform(get("/api/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(arr.size()).isEqualTo(4);
        assertThat(arr.toString()).contains("DEV").contains("LEAD").contains("DBA").contains("ADMIN");
    }

    @Test
    void admin_createAndDeleteCustomRole() throws Exception {
        String token = login("admin", "admin12345");
        MvcResult created = mvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"QA\",\"name\":\"测试\",\"description\":\"测试角色\",\"rank\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(created.getResponse().getContentAsString());
        long id = node.get("id").asLong();

        // 列表里出现自定义角色
        MvcResult list = mvc.perform(get("/api/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(list.getResponse().getContentAsString()).toString()).contains("QA");

        // 删除成功
        mvc.perform(delete("/api/roles/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void admin_cannotDeleteBuiltInRole() throws Exception {
        String token = login("admin", "admin12345");
        // 内置 DEV（内存方言 id=1）
        mvc.perform(delete("/api/roles/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("内置角色不可删除"));
    }

    @Test
    void dev_accessRoles_403() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(get("/api/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
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
}
