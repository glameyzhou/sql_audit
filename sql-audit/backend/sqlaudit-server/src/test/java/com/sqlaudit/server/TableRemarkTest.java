package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 表备注词典 + 表结构面板（契约 §6）：
 * <ul>
 *   <li>{@code PUT /api/table-remarks}：admin（instance.manage）写入 / 覆盖 / 清空
 *       （空备注 = 删除行）；dev 无 instance.manage → 403（拦截器按接口绑定判定，
 *       代码内无角色判断）；</li>
 *   <li>{@code GET /api/table-remarks}：所有登录用户可读（query.run），并做实例数据
 *       范围校验（越权 403 INSTANCE_OUT_OF_SCOPE）；</li>
 *   <li>{@code GET /api/instances/{id}/databases/{db}/tables} 新形状
 *       （{name, comment}）；{@code .../table-structure?table=} 结构面板
 *       （字段 / 类型 / 可空 / 键 / 默认值 / 注释），MySQL 目标库实测。</li>
 * </ul>
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class TableRemarkTest extends MySqlItBase {

    private long instanceA;
    private long instanceB;

    @BeforeEach
    void createInstances() throws Exception {
        String token = adminToken();
        instanceA = createBareInstance(token, "remark-a");
        instanceB = createBareInstance(token, "remark-b");
    }

    @Test
    void admin_upsert覆盖与清空() throws Exception {
        String token = adminToken();

        // 写入
        String body = perform(jsonPut("/api/table-remarks", token,
                "{\"instanceId\":" + instanceA + ",\"schemaName\":\"orders\","
                        + "\"tableName\":\"t_orders\",\"remark\":\"订单主表\"}"), 200);
        assertEquals("t_orders", json.readTree(body).path("tableName").asText());
        assertEquals("订单主表", json.readTree(body).path("remark").asText());

        // 读回
        JsonNode list = json.readTree(getJson("/api/table-remarks?instanceId=" + instanceA
                + "&schemaName=orders", token));
        assertEquals(1, list.size());
        assertEquals("t_orders", list.get(0).path("tableName").asText());
        assertEquals("订单主表", list.get(0).path("remark").asText());

        // 覆盖
        perform(jsonPut("/api/table-remarks", token,
                "{\"instanceId\":" + instanceA + ",\"schemaName\":\"orders\","
                        + "\"tableName\":\"t_orders\",\"remark\":\"订单主表（改）\"}"), 200);
        JsonNode after = json.readTree(getJson("/api/table-remarks?instanceId=" + instanceA
                + "&schemaName=orders", token));
        assertEquals(1, after.size(), "覆盖不新增行");
        assertEquals("订单主表（改）", after.get(0).path("remark").asText());

        // 清空（空串 = 删除行）
        perform(jsonPut("/api/table-remarks", token,
                "{\"instanceId\":" + instanceA + ",\"schemaName\":\"orders\","
                        + "\"tableName\":\"t_orders\",\"remark\":\"  \"}"), 200);
        JsonNode cleared = json.readTree(getJson("/api/table-remarks?instanceId=" + instanceA
                + "&schemaName=orders", token));
        assertTrue(cleared.isEmpty(), "空备注删除该行");
    }

    @Test
    void dev_无instanceManage_put403_get200() throws Exception {
        String token = adminToken();
        String devToken = devToken();

        // dev 只有 query.run + ticket.submit（DEV 角色），PUT 需 instance.manage → 403
        mvc.perform(jsonPut("/api/table-remarks", devToken,
                        "{\"instanceId\":" + instanceA + ",\"schemaName\":\"orders\","
                                + "\"tableName\":\"t_orders\",\"remark\":\"越权写\"}"))
                .andExpect(status().isForbidden());

        // GET 走 query.run（dev 持有），且 DEV 数据范围 ALL → 200 空列表
        mvc.perform(get("/api/table-remarks")
                        .param("instanceId", String.valueOf(instanceA))
                        .param("schemaName", "orders")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
    }

    @Test
    void get_实例越界403() throws Exception {
        String token = adminToken();
        long queryPerm = permissionIdOf("query.run", token);
        long scopedRole = createRole(token, "remark-viewer", "SELECTED", List.of(queryPerm), List.of(instanceA));
        long devId = userIdOf("dev", token);
        perform(jsonPut("/api/users/" + devId + "/roles", token,
                "{\"roles\":[\"remark-viewer\"]}"), 200);
        String devToken = devToken();

        // 对越界实例 B 读备注 → 403 INSTANCE_OUT_OF_SCOPE
        String body = perform(get("/api/table-remarks")
                .param("instanceId", String.valueOf(instanceB))
                .param("schemaName", "orders")
                .header("Authorization", "Bearer " + devToken), 403);
        expectCode(body, "INSTANCE_OUT_OF_SCOPE");

        // 对范围内实例 A → 200
        mvc.perform(get("/api/table-remarks")
                        .param("instanceId", String.valueOf(instanceA))
                        .param("schemaName", "orders")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
    }

    @Test
    void tables新形状与tableStructure_MySQL实测() throws Exception {
        String token = adminToken();
        ensureTarget();
        long target = createTargetInstance(token, "meta-target");

        // 表列表新形状：{name, comment}（t_demo 无注释 → comment 为 null）
        JsonNode tables = json.readTree(getJson(
                "/api/instances/" + target + "/databases/sql_audit_it_target/tables", token));
        JsonNode tDemo = null;
        for (JsonNode t : tables) {
            assertEquals(true, t.has("name"), "表列表元素含 name: " + t);
            assertTrue(t.has("comment"), "表列表元素含 comment 字段: " + t);
            if ("t_demo".equals(t.path("name").asText())) {
                tDemo = t;
            }
        }
        assertTrue(tDemo != null, "目标库表 t_demo 应在表列表中: " + tables);

        // 表结构面板：{ordinal, name, dataType, nullable, key, defaultValue, comment}
        JsonNode cols = json.readTree(getJson(
                "/api/instances/" + target + "/databases/sql_audit_it_target/table-structure"
                        + "?table=t_demo", token));
        assertEquals(3, cols.size(), "t_demo 三列: " + cols);
        JsonNode id = cols.get(0);
        assertEquals(1, id.path("ordinal").asInt());
        assertEquals("id", id.path("name").asText());
        assertEquals("PRI", id.path("key").asText(), "id 是主键");
        assertFalse(id.path("nullable").asBoolean());
        JsonNode a = null;
        for (JsonNode c : cols) {
            if ("a".equals(c.path("name").asText())) {
                a = c;
            }
        }
        assertTrue(a != null);
        assertEquals("int", a.path("dataType").asText());
        assertTrue(a.path("nullable").asBoolean(), "a 列无 NOT NULL 约束");

        // 既有列名清单端点（自动提示用）契约不变
        JsonNode names = json.readTree(getJson(
                "/api/instances/" + target + "/databases/sql_audit_it_target/columns"
                        + "?table=t_demo", token));
        assertEquals(List.of("id", "a", "b"),
                java.util.stream.StreamSupport.stream(names.spliterator(), false)
                        .map(n -> n.asText()).toList());
    }

    // ---------------- helper ----------------

    private long permissionIdOf(String code, String token) throws Exception {
        for (JsonNode p : json.readTree(getJson("/api/permissions", token))) {
            if (code.equals(p.path("code").asText())) {
                return p.path("id").asLong();
            }
        }
        throw new AssertionError("未找到权限点 " + code);
    }

    private long userIdOf(String username, String token) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", token))) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }

    private long createRole(String token, String code, String scope, List<Long> permissionIds,
                            List<Long> instanceIds) throws Exception {
        String body = perform(jsonPost("/api/roles", token,
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"instanceScope\":\"" + scope
                        + "\",\"permissionIds\":" + permissionIds + ",\"instanceIds\":" + instanceIds + "}"), 200);
        return json.readTree(body).path("id").asLong();
    }
}
