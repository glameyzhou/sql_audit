package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实例数据范围（契约 §4.4）：角色收窄到指定实例后，实例列表过滤、提交 / 查询 / 库表浏览 /
 * 回滚明细越权 403；「查看全部工单」按范围过滤。管理端 {@code /api/config/instances/**}
 * 不受数据范围限制。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class DataScopeTest extends MySqlItBase {

    private long instanceA;
    private long instanceB;

    @BeforeEach
    void createInstances() throws Exception {
        String token = adminToken();
        instanceA = createBareInstance(token, "scope-a");
        instanceB = createBareInstance(token, "scope-b");
    }

    @Test
    void devScopedToA_instancesFilteredAndOutOfScope403() throws Exception {
        String adminToken = adminToken();

        // dev 只保留一个自定义角色：提交 + 查询权限、数据范围仅 A
        long submitPerm = permissionIdOf("ticket.submit", adminToken);
        long queryPerm = permissionIdOf("query.run", adminToken);
        long scopedRole = createRole(adminToken, "dev-scoped", "SELECTED",
                List.of(submitPerm, queryPerm), List.of(instanceA));
        long devId = userIdOf("dev", adminToken);
        perform(jsonPut("/api/users/" + devId + "/roles", adminToken,
                "{\"roles\":[\"dev-scoped\"]}"), 200);
        String devToken = devToken();

        // 实例列表只显示 A
        String instances = getJson("/api/instances", devToken);
        JsonNode arr = json.readTree(instances);
        assertEquals(1, arr.size(), "dev 应只见实例 A: " + instances);
        assertEquals("scope-a", arr.get(0).path("name").asText());

        // 对 B 提交预览 / 建单 / 查询 / 库表浏览 → 403 INSTANCE_OUT_OF_SCOPE
        String previewBody = perform(jsonPost("/api/tickets/preview", devToken,
                "{\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\",\"instanceId\":"
                        + instanceB + "}"), 403);
        expectCode(previewBody, "INSTANCE_OUT_OF_SCOPE");

        String submitBody = perform(jsonPost("/api/tickets", devToken,
                "{\"title\":\"越权工单\",\"instanceId\":" + instanceB
                        + ",\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\"}"), 403);
        expectCode(submitBody, "INSTANCE_OUT_OF_SCOPE");

        String queryBody = perform(jsonPost("/api/query", devToken,
                "{\"instanceId\":" + instanceB + ",\"sql\":\"SELECT 1\",\"dialect\":\"MYSQL\"}"), 403);
        expectCode(queryBody, "INSTANCE_OUT_OF_SCOPE");

        String metaBody = perform(get("/api/instances/" + instanceB + "/databases")
                .header("Authorization", "Bearer " + devToken), 403);
        expectCode(metaBody, "INSTANCE_OUT_OF_SCOPE");

        // 对 A 的预览正常（实例无凭据，元数据规则自动跳过，不 500）
        mvc.perform(jsonPost("/api/tickets/preview", devToken,
                        "{\"sql\":\"UPDATE t_demo SET a=1 WHERE id=1\",\"dialect\":\"MYSQL\",\"instanceId\":"
                                + instanceA + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void ticketsAll_filteredByScope() throws Exception {
        String adminToken = adminToken();

        // viewer1：仅「查看全部工单」能力、数据范围仅 A
        long viewAllPerm = permissionIdOf("ticket.view_all", adminToken);
        long viewerRole = createRole(adminToken, "viewer", "SELECTED", List.of(viewAllPerm), List.of(instanceA));
        long viewerId = createUser(adminToken, "viewer1", "viewer12345");
        perform(jsonPut("/api/users/" + viewerId + "/roles", adminToken,
                "{\"roles\":[\"viewer\"]}"), 200);
        String viewerToken = login("viewer1", "viewer12345");

        // admin 在 B 上有一张工单（直接落库，聚焦数据范围过滤本身）
        insertTicketFixture(adminToken, instanceB, "B 实例工单");

        // viewer1 的「全部工单」为空（B 越界被过滤）；admin 能看到 1 张（分页响应 {items,total}）
        JsonNode viewerTickets = json.readTree(getJson("/api/tickets/all", viewerToken));
        assertEquals(0, viewerTickets.path("items").size(), "viewer1 不应看到 B 实例工单");
        assertEquals(0, viewerTickets.path("total").asLong());
        JsonNode adminTickets = json.readTree(getJson("/api/tickets/all", adminToken));
        assertEquals(1, adminTickets.path("items").size());
        assertEquals(1, adminTickets.path("total").asLong());
    }

    @Test
    void rollbackStatements_outOfScope403() throws Exception {
        String adminToken = adminToken();

        // dev 只留提交 + 查询 + 回滚查看、数据范围仅 A（回滚端点需要 rollback.view 过拦截器）
        long submitPerm = permissionIdOf("ticket.submit", adminToken);
        long queryPerm = permissionIdOf("query.run", adminToken);
        long rollbackViewPerm = permissionIdOf("rollback.view", adminToken);
        long scopedRole = createRole(adminToken, "dev-scoped2", "SELECTED",
                List.of(submitPerm, queryPerm, rollbackViewPerm), List.of(instanceA));
        long devId = userIdOf("dev", adminToken);
        perform(jsonPut("/api/users/" + devId + "/roles", adminToken,
                "{\"roles\":[\"dev-scoped2\"]}"), 200);
        String devToken = devToken();

        // admin 在 B 上有一张工单 + 执行单元，再手工挂一条回滚主记录（引用该 unit）
        long unitId = insertTicketFixture(adminToken, instanceB, "回滚范围工单");
        jdbc.update("INSERT INTO t_rollback_sql (unit_id, stmt_count, total_bytes, generator, status, coverage) "
                + "VALUES (?, 0, 0, 'MIRROR', 'generated', 'FULL')", unitId);
        long rollbackId = jdbc.queryForObject("SELECT id FROM t_rollback_sql WHERE unit_id=?", Long.class, unitId);

        // dev（仅 A）看反向语句明细 → 403；admin（ALL）→ 200
        String outBody = perform(get("/api/rollbacks/" + rollbackId + "/statements")
                .header("Authorization", "Bearer " + devToken), 403);
        expectCode(outBody, "INSTANCE_OUT_OF_SCOPE");
        mvc.perform(get("/api/rollbacks/" + rollbackId + "/statements")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        // dev 的回滚列表为空；admin 能看到 1 条（分页响应 {items,total}）
        JsonNode devRollbacks = json.readTree(getJson("/api/rollbacks", devToken));
        assertEquals(0, devRollbacks.path("items").size());
        assertEquals(0, devRollbacks.path("total").asLong());
        JsonNode adminRollbacks = json.readTree(getJson("/api/rollbacks", adminToken));
        assertEquals(1, adminRollbacks.path("items").size());
        assertEquals(1, adminRollbacks.path("total").asLong());
    }

    @Test
    void configInstances_unaffectedByDataScope() throws Exception {
        String adminToken = adminToken();
        long devId = userIdOf("dev", adminToken);
        long submitPerm = permissionIdOf("ticket.submit", adminToken);
        long queryPerm = permissionIdOf("query.run", adminToken);
        long scopedRole = createRole(adminToken, "dev-scoped3", "SELECTED",
                List.of(submitPerm, queryPerm), List.of(instanceA));
        perform(jsonPut("/api/users/" + devId + "/roles", adminToken,
                "{\"roles\":[\"dev-scoped3\"]}"), 200);

        // 管理端点由 instance.manage 权限控制（dev 无 → 403 拦截器），与数据范围无关
        mvc.perform(get("/api/config/instances").header("Authorization", "Bearer " + devToken()))
                .andExpect(status().isForbidden());
        // admin 的管理列表不受范围影响（见全部实例）
        JsonNode all = json.readTree(getJson("/api/config/instances", adminToken));
        assertTrue(all.size() >= 2);
    }

    // ---------------- helper ----------------

    /** 直接落库一张工单 + 执行单元（B 实例），返回 unit id。 */
    private long insertTicketFixture(String token, long instanceId, String title) throws Exception {
        long adminId = userIdOf("admin", token);
        jdbc.update("INSERT INTO t_ticket (title, creator_id, instance_id, risk_level, status, trace_id) "
                + "VALUES (?, ?, ?, 'LOW', 'auditing', ?)", title, adminId, instanceId, "trace-" + title);
        long ticketId = jdbc.queryForObject("SELECT MAX(id) FROM t_ticket", Long.class);
        // statement 列是密文（存储层透明加密）：按应用的 SqlCipher（同 JVM 默认开发密钥）加密后落库，
        // 否则后续 DAO 读取（回滚范围校验经 unit → instance）解密失败
        byte[] encrypted = new com.sqlaudit.common.crypto.SqlCipher().encrypt("SELECT 1");
        jdbc.update("INSERT INTO t_ticket_unit (ticket_id, instance_id, unit_order, statement, "
                        + "statement_hash, statement_type, status, intent_written, created_at) "
                        + "VALUES (?, ?, 1, ?, ?, 'SELECT', 'pending', 0, NOW())",
                ticketId, instanceId, encrypted, "unit-" + ticketId);
        return jdbc.queryForObject("SELECT MAX(id) FROM t_ticket_unit", Long.class);
    }

    private long userIdOf(String username, String token) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", token))) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
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

    private long createUser(String token, String username, String password) throws Exception {
        String body = perform(jsonPost("/api/users", token,
                "{\"username\":\"" + username + "\",\"displayName\":\"" + username
                        + "\",\"password\":\"" + password + "\"}"), 200);
        return json.readTree(body).path("id").asLong();
    }
}
