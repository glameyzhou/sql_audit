package com.sqlaudit.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlaudit.server.test.MySqlItBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端分页端点（契约 §6）：/api/tickets、/api/tickets/all、/api/rollbacks、
 * /api/audit-logs 统一返回 {@code {items, total}}；page ≥ 1、size ≤ 200 钳制不报错；
 * 回滚列表支持可选 ticketId 过滤（工单详情「单元 → 回滚记录」映射用）。
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class PagedListTest extends MySqlItBase {

    @Test
    void tickets_pagination_total_pageClamp_sizeClamp() throws Exception {
        String adminToken = adminToken();
        String devToken = devToken();
        long devId = userIdOf("dev", adminToken);
        long adminId = userIdOf("admin", adminToken);

        // dev 名下 5 张工单（created_at 递减，明确分页顺序），另给 admin 落 1 张
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        for (int i = 0; i < 5; i++) {
            insertTicketFixture(devId, 1L, "dev-工单-" + i, base.plusMinutes(i));
        }
        insertTicketFixture(adminId, 2L, "admin-工单", base.plusMinutes(10));

        // 第 1 页：2 条、total 5、最新在前
        JsonNode page1 = readJson(get("/api/tickets").param("page", "1").param("size", "2")
                .header("Authorization", "Bearer " + devToken));
        assertEquals(2, page1.path("items").size());
        assertEquals(5, page1.path("total").asLong());
        assertEquals("dev-工单-4", page1.path("items").get(0).path("title").asText());

        // 第 3 页（尾页）：1 条
        JsonNode page3 = readJson(get("/api/tickets").param("page", "3").param("size", "2")
                .header("Authorization", "Bearer " + devToken));
        assertEquals(1, page3.path("items").size());
        assertEquals(5, page3.path("total").asLong());
        assertEquals("dev-工单-0", page3.path("items").get(0).path("title").asText());

        // 越界钳制：page=0 → 按第 1 页；size=999 → 上限 200（5 条全出）
        JsonNode clamped = readJson(get("/api/tickets").param("page", "0").param("size", "999")
                .header("Authorization", "Bearer " + devToken));
        assertEquals(5, clamped.path("items").size());
        assertEquals(5, clamped.path("total").asLong());

        // 超出范围的页码：空 items、total 不变
        JsonNode beyond = readJson(get("/api/tickets").param("page", "99").param("size", "2")
                .header("Authorization", "Bearer " + devToken));
        assertEquals(0, beyond.path("items").size());
        assertEquals(5, beyond.path("total").asLong());

        // 全部工单（admin，数据范围 ALL）：6 张
        JsonNode all = readJson(get("/api/tickets/all").param("page", "1").param("size", "10")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(6, all.path("total").asLong());
        assertEquals(6, all.path("items").size());
    }

    @Test
    void auditLogs_pagination_seqRangeBounds() throws Exception {
        String adminToken = adminToken();
        // 直插 5 条审计记录（seq 1..5；本测试不校验 hash 链，prev/hash 占位即可）
        for (long seq = 1; seq <= 5; seq++) {
            jdbc.update("INSERT INTO t_audit_log (seq, actor_id, actor_name, actor_role, action, "
                            + "target_type, target_id, result, trace_id, prev_hash, hash, created_at) "
                            + "VALUES (?, 1, 'alice', 'DEV', 'QUERY_EXECUTED', 'instance', '1', "
                            + "'SUCCESS', ?, 'p', 'h', NOW())",
                    seq, "trace-" + seq);
        }

        // 默认 seq 区间（1..100）第 1 页 3 条
        JsonNode page1 = readJson(get("/api/audit-logs").param("page", "1").param("size", "3")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(3, page1.path("items").size());
        assertEquals(5, page1.path("total").asLong());
        assertEquals(1L, page1.path("items").get(0).path("seq").asLong());

        // 第 2 页：seq 升序，取到 4、5
        JsonNode page2 = readJson(get("/api/audit-logs").param("page", "2").param("size", "3")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(2, page2.path("items").size());
        assertEquals(4L, page2.path("items").get(0).path("seq").asLong());
        assertEquals(5L, page2.path("items").get(1).path("seq").asLong());

        // 显式 seq 区间收窄 total（fromSeq/toSeq 默认值不变，仍是 1/100）
        JsonNode narrowed = readJson(get("/api/audit-logs")
                .param("fromSeq", "2").param("toSeq", "4").param("page", "1").param("size", "10")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(3, narrowed.path("total").asLong());
        assertEquals(2L, narrowed.path("items").get(0).path("seq").asLong());
    }

    @Test
    void rollbacks_pagination_total_ticketIdFilter() throws Exception {
        String adminToken = adminToken();
        long adminId = userIdOf("admin", adminToken);

        // 工单 A 挂 2 条回滚，工单 B 挂 1 条
        long ticketA = insertTicketWithUnit(adminId, 1L, "A");
        long unitA2 = insertUnit(ticketA, 1L, 2);
        long ticketB = insertTicketWithUnit(adminId, 1L, "B");
        long unitB = jdbc.queryForObject("SELECT MIN(id) FROM t_ticket_unit WHERE ticket_id=?",
                Long.class, ticketB);
        insertRollback(unitA2);
        long unitA1 = jdbc.queryForObject("SELECT MIN(id) FROM t_ticket_unit WHERE ticket_id=?",
                Long.class, ticketA);
        insertRollback(unitA1);
        insertRollback(unitB);

        // 分页：第 1 页 2 条（id 倒序）、total 3
        JsonNode page1 = readJson(get("/api/rollbacks").param("page", "1").param("size", "2")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(2, page1.path("items").size());
        assertEquals(3, page1.path("total").asLong());

        // 第 2 页：1 条
        JsonNode page2 = readJson(get("/api/rollbacks").param("page", "2").param("size", "2")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(1, page2.path("items").size());
        assertEquals(3, page2.path("total").asLong());

        // ticketId 过滤：工单 A 只有 2 条（不分页参数时默认 size=20 全出）
        JsonNode filtered = readJson(get("/api/rollbacks").param("ticketId", String.valueOf(ticketA))
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(2, filtered.path("items").size());
        assertEquals(2, filtered.path("total").asLong());
        for (JsonNode item : filtered.path("items")) {
            assertEquals(ticketA, item.path("ticketId").asLong());
        }

        // 不存在的工单：空
        JsonNode none = readJson(get("/api/rollbacks").param("ticketId", "999999")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(0, none.path("items").size());
        assertEquals(0, none.path("total").asLong());
    }

    // ---------------- helper ----------------

    private JsonNode readJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        var result = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return json.readTree(bodyOf(result));
    }

    private long userIdOf(String username, String adminToken) throws Exception {
        for (JsonNode u : json.readTree(getJson("/api/users", adminToken))) {
            if (username.equals(u.path("username").asText())) {
                return u.path("id").asLong();
            }
        }
        throw new AssertionError("未找到用户 " + username);
    }

    /** 直插工单（无执行单元），返回 ticket id。 */
    private long insertTicketFixture(long creatorId, long instanceId, String title, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO t_ticket (title, creator_id, instance_id, risk_level, status, trace_id, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'LOW', 'auditing', ?, ?, ?)",
                title, creatorId, instanceId, "trace-" + title,
                createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return jdbc.queryForObject("SELECT MAX(id) FROM t_ticket", Long.class);
    }

    /** 直插工单 + 1 个执行单元，返回 ticket id。 */
    private long insertTicketWithUnit(long creatorId, long instanceId, String title) {
        long ticketId = insertTicketFixture(creatorId, instanceId, title,
                LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        insertUnit(ticketId, instanceId, 1);
        return ticketId;
    }

    /** 直插执行单元（statement 用同 JVM 默认开发密钥的 SqlCipher 加密，保证 DAO 解密可读）。 */
    private long insertUnit(long ticketId, long instanceId, int unitOrder) {
        byte[] encrypted = new com.sqlaudit.common.crypto.SqlCipher().encrypt("SELECT 1");
        jdbc.update("INSERT INTO t_ticket_unit (ticket_id, instance_id, unit_order, statement, "
                        + "statement_hash, statement_type, status, intent_written, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'SELECT', 'pending', 0, NOW())",
                ticketId, instanceId, unitOrder, encrypted, "unit-" + ticketId + "-" + unitOrder);
        return jdbc.queryForObject("SELECT MAX(id) FROM t_ticket_unit", Long.class);
    }

    /** 直插回滚主记录（引用 unit），返回 rollback id。 */
    private long insertRollback(long unitId) {
        jdbc.update("INSERT INTO t_rollback_sql (unit_id, stmt_count, total_bytes, generator, status, coverage) "
                + "VALUES (?, 0, 0, 'MIRROR', 'generated', 'FULL')", unitId);
        return jdbc.queryForObject("SELECT MAX(id) FROM t_rollback_sql", Long.class);
    }
}
