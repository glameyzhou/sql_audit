package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色 / 用户 / 权限目录（契约 §6 角色与权限段 / 用户段）：角色 CRUD（功能权限 + 数据范围）、
 * 权限目录只读（无写接口）、用户创建不带角色 + 独立分配角色、内置角色只读 code。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class RbacRoleUserTest extends MySqlItBase {

    @Test
    void rolesList_showsBuiltinRolesWithGrants() throws Exception {
        String token = adminToken();
        JsonNode roles = json.readTree(getJson("/api/roles", token));
        assertEquals(5, roles.size(), "5 个内置角色");

        JsonNode superAdmin = findRole(roles, "SUPER_ADMIN");
        assertTrue(superAdmin.path("builtin").asBoolean());
        assertEquals("ALL", superAdmin.path("instanceScope").asText());
        assertEquals(13, superAdmin.path("permissionIds").size(), "SUPER_ADMIN 持有全部 13 个非 all_users 权限点");
        assertTrue(superAdmin.path("userCount").asInt() >= 1);

        JsonNode dev = findRole(roles, "DEV");
        assertEquals(2, dev.path("permissionIds").size(), "DEV 持有 ticket.submit + query.run");
        assertFalse(dev.path("meta").asBoolean());
    }

    @Test
    void roleCrud_fullCycle() throws Exception {
        String token = adminToken();
        long submitPerm = permissionIdOf("ticket.submit", token);
        long viewAllPerm = permissionIdOf("ticket.view_all", token);
        long instanceId = createInstance(token, "role-target");

        // 创建
        long id = createRole(token, "custom-role", "SELECTED", List.of(submitPerm, viewAllPerm), List.of(instanceId));
        JsonNode created = findRole(json.readTree(getJson("/api/roles", token)), "custom-role");
        assertFalse(created.path("builtin").asBoolean());
        assertEquals("SELECTED", created.path("instanceScope").asText());
        assertEquals(0, created.path("userCount").asInt());

        // 更新（名称 / 范围 / 权限 / 实例整体替换）
        mvc.perform(jsonPut("/api/roles/" + id, token,
                        "{\"name\":\"改名角色\",\"instanceScope\":\"ALL\",\"permissionIds\":[" + submitPerm
                                + "],\"instanceIds\":[]}"))
                .andExpect(status().isOk());
        JsonNode updated = findRole(json.readTree(getJson("/api/roles", token)), "custom-role");
        assertEquals("改名角色", updated.path("name").asText());
        assertEquals("ALL", updated.path("instanceScope").asText());
        assertEquals(List.of(submitPerm), toLongList(updated.path("permissionIds")));

        // 删除
        mvc.perform(delete("/api/roles/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void userCreate_withoutRoles_thenAssignRoles() throws Exception {
        String token = adminToken();

        // 创建不带角色
        String body = perform(jsonPost("/api/users", token,
                "{\"username\":\"u1\",\"displayName\":\"用户一\",\"email\":\"u1@x.com\",\"password\":\"u112345\"}"), 200);
        long id = json.readTree(body).path("id").asLong();
        JsonNode users = json.readTree(getJson("/api/users", token));
        JsonNode u1 = null;
        for (JsonNode u : users) {
            if ("u1".equals(u.path("username").asText())) {
                u1 = u;
            }
        }
        assertEquals(0, u1.path("roles").size(), "创建不带角色");
        assertEquals("用户一", u1.path("displayName").asText());
        assertEquals("u1@x.com", u1.path("email").asText());

        // 分配角色（rbac.manage）
        mvc.perform(jsonPut("/api/users/" + id + "/roles", token, "{\"roles\":[\"DEV\",\"LEAD\"]}"))
                .andExpect(status().isOk());
        String usersAfter = getJson("/api/users", token);
        for (JsonNode u : json.readTree(usersAfter)) {
            if ("u1".equals(u.path("username").asText())) {
                // 按角色 id 升序：LEAD(4) < DEV(5)
                assertEquals(List.of("LEAD", "DEV"),
                        toTextList(u.path("roles"), "code"));
            }
        }

        // 重复用户名 → 409
        String dup = perform(jsonPost("/api/users", token,
                "{\"username\":\"u1\",\"displayName\":\"x\",\"password\":\"whatever1\"}"), 409);
        expectCode(dup, "USERNAME_EXISTS");
    }

    @Test
    void permissionCatalog_readOnly() throws Exception {
        String token = adminToken();
        JsonNode catalog = json.readTree(getJson("/api/permissions", token));
        assertEquals(14, catalog.size(), "14 个权限点");

        JsonNode basic = findPermission(catalog, "basic");
        assertTrue(basic.path("allUsers").asBoolean());
        assertEquals(9, basic.path("apis").size());
        assertEquals(0, basic.path("roles").size(), "all_users 权限点不写授权行");

        JsonNode rbacManage = findPermission(catalog, "rbac.manage");
        assertTrue(rbacManage.path("meta").asBoolean());
        assertTrue(!rbacManage.path("apis").isEmpty());
        assertEquals(List.of("SUPER_ADMIN"), toTextList(rbacManage.path("roles"), "code"));

        // 目录无写接口（POST /api/permissions 已删除 → 405）
        mvc.perform(jsonPost("/api/permissions", token, "[]"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void dev_cannotCallRbacEndpoints() throws Exception {
        String devToken = devToken();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/roles").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());
    }

    // ---------------- helper ----------------

    private JsonNode findRole(JsonNode roles, String code) {
        for (JsonNode r : roles) {
            if (code.equals(r.path("code").asText())) {
                return r;
            }
        }
        throw new AssertionError("未找到角色 " + code);
    }

    private JsonNode findPermission(JsonNode catalog, String code) {
        for (JsonNode p : catalog) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        throw new AssertionError("未找到权限点 " + code);
    }

    private List<Long> toLongList(JsonNode arr) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private List<String> toTextList(JsonNode arr, String field) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private long permissionIdOf(String code, String token) throws Exception {
        for (JsonNode p : json.readTree(getJson("/api/permissions", token))) {
            if (code.equals(p.path("code").asText())) {
                return p.path("id").asLong();
            }
        }
        throw new AssertionError("未找到权限点 " + code);
    }

    private long createRole(String token, String code, String scope, List<Long> permissionIds,
                            List<Long> instanceIds) throws Exception {
        String body = perform(jsonPost("/api/roles", token,
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"instanceScope\":\"" + scope
                        + "\",\"permissionIds\":" + permissionIds + ",\"instanceIds\":" + instanceIds + "}"), 200);
        return json.readTree(body).path("id").asLong();
    }

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
