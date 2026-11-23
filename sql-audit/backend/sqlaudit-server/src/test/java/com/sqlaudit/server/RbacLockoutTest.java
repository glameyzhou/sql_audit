package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 防自锁不变式（契约 §4.3）：撤角色 / 改角色权限 / 停用 / 删角色四条路径 409 + 数据不变；
 * 并发两次移除至多一次成功；内置角色保护；权限收敛（对 meta 持有者操作的 403）。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class RbacLockoutTest extends MySqlItBase {

    // ---------------- 不变式：admin 是种子中唯一 meta 持有者 ----------------

    @Test
    void removeSuperAdminRoleFromAdmin_409_lockout() throws Exception {
        String token = adminToken();
        long adminId = userIdOf("admin", token);

        String body = perform(jsonPut("/api/users/" + adminId + "/roles", token,
                "{\"roles\":[\"ADMIN\",\"DBA\",\"LEAD\",\"DEV\"]}"), 409);
        expectCode(body, "RBAC_LOCKOUT");

        // 数据不变：admin 仍持有 SUPER_ADMIN
        String users = getJson("/api/users", token);
        JsonNode admin = findUser(users, "admin");
        List<String> codes = new ArrayList<>();
        admin.path("roles").forEach(r -> codes.add(r.path("code").asText()));
        assertTrue(codes.contains("SUPER_ADMIN"), "防自锁回滚后 admin 应仍持有 SUPER_ADMIN: " + codes);
    }

    @Test
    void disableAdmin_409_lockout() throws Exception {
        String token = adminToken();
        long adminId = userIdOf("admin", token);
        String body = perform(jsonPut("/api/users/" + adminId, token, "{\"status\":\"DISABLED\"}"), 409);
        expectCode(body, "RBAC_LOCKOUT");
    }

    @Test
    void stripMetaFromSuperAdminRole_409_lockout() throws Exception {
        String token = adminToken();
        long roleId = roleIdOf("SUPER_ADMIN", token);
        List<Long> withoutMeta = permissionIdsOf(token, false); // 全部非 meta 权限点

        String body = perform(jsonPut("/api/roles/" + roleId, token,
                "{\"name\":\"超级管理员\",\"instanceScope\":\"ALL\",\"permissionIds\":" + withoutMeta
                        + ",\"instanceIds\":[]}"), 409);
        expectCode(body, "RBAC_LOCKOUT");

        // 角色权限未被改动：SUPER_ADMIN 仍持有 rbac.manage
        String roles = getJson("/api/roles", token);
        JsonNode superAdmin = findRole(roles, "SUPER_ADMIN");
        List<Long> permissionIds = new ArrayList<>();
        superAdmin.path("permissionIds").forEach(n -> permissionIds.add(n.asLong()));
        assertTrue(permissionIds.contains(permissionIdOf("rbac.manage", token)), "rbac.manage 应仍被持有");
    }

    @Test
    void deleteBuiltinRole_400() throws Exception {
        String token = adminToken();
        long roleId = roleIdOf("DBA", token);
        String body = perform(delete("/api/roles/" + roleId)
                .header("Authorization", "Bearer " + token), 400);
        expectCode(body, "BUILTIN_ROLE_IMMUTABLE");
    }

    @Test
    void concurrentRemoveLockout_atMostOneSucceeds() throws Exception {
        String token = adminToken();
        long adminId = userIdOf("admin", token);
        long devId = userIdOf("dev", token);

        // 两个 meta 持有者：admin（SUPER_ADMIN）+ dev（backup-meta）。并发互撤时，
        // 若无串行化两边都能通过前置检查 → 全部被撤（0 持有者）；串行化保证至多一次成功。
        long rbacManageId = permissionIdOf("rbac.manage", token);
        createRole(token, "backup-meta", "ALL", List.of(rbacManageId), List.of());
        perform(jsonPut("/api/users/" + devId + "/roles", token,
                "{\"roles\":[\"DEV\",\"backup-meta\"]}"), 200);

        String stripAdmin = "{\"roles\":[\"ADMIN\",\"DBA\",\"LEAD\",\"DEV\"]}";
        String stripDev = "{\"roles\":[\"DEV\"]}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = pool.submit(() -> {
                start.await();
                return mvc.perform(jsonPut("/api/users/" + adminId + "/roles", token, stripAdmin))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> f2 = pool.submit(() -> {
                start.await();
                return mvc.perform(jsonPut("/api/users/" + devId + "/roles", token, stripDev))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int s1 = f1.get(30, TimeUnit.SECONDS);
            int s2 = f2.get(30, TimeUnit.SECONDS);

            long ok = (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0);
            assertEquals(1, ok, "并发互撤两个 meta 持有者应至多一次成功（实际状态 "
                    + s1 + " / " + s2 + "）");
            assertTrue(s1 == 409 || s2 == 409, "另一个必须是 409");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 正向路径：先补一个 meta 持有者，撤旧的不锁死 ----------------

    @Test
    void removingMetaHolderSucceeds_whenAnotherHolderExists() throws Exception {
        String token = adminToken();
        long devId = userIdOf("dev", token);
        long rbacManageId = permissionIdOf("rbac.manage", token);

        // dev 经自定义角色持有 meta → 系统有两个 meta 持有者
        long metaRoleId = createRole(token, "backup-meta", "SELECTED", List.of(rbacManageId), List.of());
        perform(jsonPut("/api/users/" + devId + "/roles", token,
                "{\"roles\":[\"DEV\",\"backup-meta\"]}"), 200);

        // 现在撤掉 admin 的 SUPER_ADMIN 不再锁死
        long adminId = userIdOf("admin", token);
        perform(jsonPut("/api/users/" + adminId + "/roles", token,
                "{\"roles\":[\"ADMIN\",\"DBA\",\"LEAD\",\"DEV\"]}"), 200);

        // admin 已失去 SUPER_ADMIN（不再有 rbac.manage）；dev 经 backup-meta 持有 rbac.manage，
        // 由 dev 撤掉自己的 backup-meta → 系统将失去唯一 meta 持有者 → 409
        String devToken = login("dev", "dev12345");
        String body = perform(jsonPut("/api/users/" + devId + "/roles", devToken, "{\"roles\":[\"DEV\"]}"), 409);
        expectCode(body, "RBAC_LOCKOUT");
    }

    // ---------------- 权限收敛（§4.3） ----------------

    @Test
    void nonMetaOperator_cannotResetMetaHolderPassword() throws Exception {
        String token = adminToken();
        // ops 只持 ADMIN（无 meta），是「可管用户」但非「可管 meta 持有者」
        long opsId = createUser(token, "ops", "ops12345");
        long adminRoleId = roleIdOf("ADMIN", token);
        perform(jsonPut("/api/users/" + opsId + "/roles", token, "{\"roles\":[\"ADMIN\"]}"), 200);
        String opsToken = login("ops", "ops12345");

        long adminId = userIdOf("admin", token);
        String body = perform(jsonPut("/api/users/" + adminId + "/password", opsToken,
                "{\"password\":\"hacked123\"}"), 403);
        expectCode(body, "FORBIDDEN");
    }

    @Test
    void metaOperator_canResetMetaHolderPassword() throws Exception {
        String token = adminToken();
        long adminId = userIdOf("admin", token);
        perform(jsonPut("/api/users/" + adminId + "/password", token, "{\"password\":\"rotated123\"}"), 200);
        // 改密生效
        login("admin", "rotated123");
    }

    @Test
    void roleCrud_normalPathSucceeds() throws Exception {
        String token = adminToken();
        long id = createRole(token, "ops-role", "ALL", List.of(), List.of());
        perform(delete("/api/roles/" + id).header("Authorization", "Bearer " + token), 200);
    }

    // ---------------- helper ----------------

    private long userIdOf(String username, String token) throws Exception {
        JsonNode users = json.readTree(getJson("/api/users", token));
        for (JsonNode u : users) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }

    private long roleIdOf(String code, String token) throws Exception {
        for (JsonNode r : json.readTree(getJson("/api/roles", token))) {
            if (code.equals(r.path("code").asText())) {
                return r.path("id").asLong();
            }
        }
        throw new AssertionError("未找到角色 " + code);
    }

    private long permissionIdOf(String code, String token) throws Exception {
        for (JsonNode p : json.readTree(getJson("/api/permissions", token))) {
            if (code.equals(p.path("code").asText())) {
                return p.path("id").asLong();
            }
        }
        throw new AssertionError("未找到权限点 " + code);
    }

    /** 全部非 meta（且非 all_users）权限点 id。 */
    private List<Long> permissionIdsOf(String token, boolean includeMeta) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode p : json.readTree(getJson("/api/permissions", token))) {
            if (p.path("allUsers").asBoolean()) {
                continue;
            }
            if (includeMeta || !p.path("meta").asBoolean()) {
                ids.add(p.path("id").asLong());
            }
        }
        return ids;
    }

    private long createRole(String token, String code, String scope, List<Long> permissionIds,
                            List<Long> instanceIds) throws Exception {
        String body = perform(jsonPost("/api/roles", token,
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"instanceScope\":\"" + scope
                        + "\",\"permissionIds\":" + permissionIds + ",\"instanceIds\":" + instanceIds + "}"), 200);
        return json.readTree(body).path("id").asLong();
    }

    private long createUser(String token, String username, String password) throws Exception {
        String body = perform(jsonPost("/api/users", token,
                "{\"username\":\"" + username + "\",\"displayName\":\"" + username
                        + "\",\"password\":\"" + password + "\"}"), 200);
        return json.readTree(body).path("id").asLong();
    }

    private JsonNode findUser(String usersBody, String username) throws Exception {
        for (JsonNode u : json.readTree(usersBody)) {
            if (username.equals(u.path("username").asText())) {
                return u;
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }

    private JsonNode findRole(String rolesBody, String code) throws Exception {
        for (JsonNode r : json.readTree(rolesBody)) {
            if (code.equals(r.path("code").asText())) {
                return r;
            }
        }
        throw new AssertionError("未找到角色 " + code);
    }
}
