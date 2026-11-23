package com.sqlaudit.server;

import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与鉴权矩阵（契约 §4.2 / §6 认证段）：登录、/auth/me 的 UserView（含 apis 能力清单）、
 * 401/403 矩阵、停用账号即时失效。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class AuthApiTest extends MySqlItBase {

    @Test
    void login_returnsTokenAndUserView() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.displayName").isNotEmpty())
                .andExpect(jsonPath("$.user.roles[0].code").isNotEmpty())
                .andExpect(jsonPath("$.user.roles[0].name").isNotEmpty())
                .andExpect(jsonPath("$.user.apis").isArray());
    }

    @Test
    void login_wrongPassword_401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_disabledUser_401() throws Exception {
        jdbc.update("UPDATE t_user SET status='DISABLED' WHERE username='dev'");
        mvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dev\",\"password\":\"dev12345\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withoutToken_401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returnsApisForAdmin_andForDev() throws Exception {
        String adminToken = adminToken();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                // 超级管理员持有全部非 all_users 权限点
                .andExpect(jsonPath("$.apis").value(org.hamcrest.Matchers.hasItem("GET /api/users")))
                .andExpect(jsonPath("$.apis").value(org.hamcrest.Matchers.hasItem("POST /api/query")));

        String devToken = devToken();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dev"))
                // dev 只有提交与查询
                .andExpect(jsonPath("$.apis").value(org.hamcrest.Matchers.hasItem("POST /api/query")))
                .andExpect(jsonPath("$.apis").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("GET /api/users"))));
    }

    @Test
    void unauthenticated_protectedEndpoint_401() throws Exception {
        mvc.perform(get("/api/config/instances"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"sql\":\"SELECT 1\",\"dialect\":\"MYSQL\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dev_onAdminEndpoint_403() throws Exception {
        String devToken = devToken();
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/roles").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_hasFullAccess() throws Exception {
        String token = adminToken();
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void disabledUser_tokenImmediatelyInvalid() throws Exception {
        // dev 先登录拿到 token；随后被管理员经 API 停用（真实写路径 + 缓存即时失效）
        String devToken = devToken();
        long devId = userIdOf("dev", adminToken());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/users/" + devId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // 停用立即生效：旧 token 无需等缓存 TTL / token 过期
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isUnauthorized());
    }

    /** 从 GET /api/users 里按用户名找 id（种子账号 id 不写死）。 */
    private long userIdOf(String username, String adminToken) throws Exception {
        String body = getJson("/api/users", adminToken);
        for (var node : json.readTree(body)) {
            if (username.equals(node.path("username").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }

    @Test
    void springGroundFacts_trailingSlashMismatchAndHeadFold() throws Exception {
        // Spring 6 默认尾斜杠不匹配（PathPatternParser）：/api/tickets/ 落不到 GET /api/tickets
        mvc.perform(get("/api/tickets/").header("Authorization", "Bearer " + devToken()))
                .andExpect(status().isNotFound());

        // @GetMapping 自动支持 HEAD：拦截器把 HEAD 折叠为 GET 查表后放行
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .head("/api/tickets").header("Authorization", "Bearer " + devToken()))
                .andExpect(status().isOk());
    }

    @Test
    void basicEndpoints_openToAllLoggedIn() throws Exception {
        String devToken = devToken();
        mvc.perform(get("/api/directory/users").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/directory/roles").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='LEAD')].name").value("主管"));
        mvc.perform(get("/api/approvals/pending").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
    }
}
