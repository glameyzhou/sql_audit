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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限配置端点集成测试（memory profile）。
 *
 * <p>覆盖：元权限 ADMIN 拦截、矩阵返回、界面配置覆盖兜底的端到端链路（配后即时生效）。
 * 覆盖用例在 finally 中恢复默认，避免污染共享内存存储。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermissionConfigTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void admin_listMatrix_200() throws Exception {
        String token = login("admin", "admin12345");
        MvcResult result = mvc.perform(get("/api/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(arr.size()).isEqualTo(9);

        JsonNode meta = null;
        for (JsonNode n : arr) {
            if (n.get("code").asText().equals("PERMISSION_ADMIN")) {
                meta = n;
            }
        }
        assertThat(meta).isNotNull();
        assertThat(meta.get("configurable").asBoolean()).isFalse();
        assertThat(meta.get("roles").toString()).contains("ADMIN");
    }

    @Test
    void dev_accessPermissions_403() throws Exception {
        String token = login("dev", "dev12345");
        mvc.perform(get("/api/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_grantDev_thenDevCanConfigureRules() throws Exception {
        String adminToken = login("admin", "admin12345");
        String devToken = login("dev", "dev12345");

        // 兜底：dev 不能创建规则（CONFIG_RULE_FLOW 默认 DBA/ADMIN）
        mvc.perform(post("/api/config/rules").header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson()))
                .andExpect(status().isForbidden());

        try {
            // 界面配置：把 CONFIG_RULE_FLOW 授予 DEV
            mvc.perform(post("/api/permissions").header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"code\":\"CONFIG_RULE_FLOW\",\"roles\":[\"DEV\"]}]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied.length()").value(1));

            // dev 现在可以创建规则（覆盖兜底生效）
            mvc.perform(post("/api/config/rules").header("Authorization", "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson()))
                    .andExpect(status().isOk());
        } finally {
            // 恢复默认 DBA/ADMIN，避免污染后续用例
            mvc.perform(post("/api/permissions").header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"code\":\"CONFIG_RULE_FLOW\",\"roles\":[\"DBA\",\"ADMIN\"]}]"))
                    .andExpect(status().isOk());
        }
    }

    private static String ruleJson() {
        return "{\"code\":\"R_TEST_OVERRIDE\",\"dialect\":\"ALL\",\"statementType\":\"ALL\","
                + "\"severity\":\"WARN\",\"enabled\":true,\"description\":\"test\"}";
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
