package com.sqlaudit.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MySQL profile 端到端冒烟（真 Docker MySQL 8.0.46）：验证 JDBC 真 DAO 装配 +
 * Flyway 迁移 + 提交→审批全链路落真库。
 *
 * <p>前置：本地 MySQL 可达（jdbc:mysql://127.0.0.1:3306/sql-audit, root/password）。
 * 无库环境请跳过本类（@Tag("mysql")）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MysqlProfileSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void endToEnd_onRealMysql() throws Exception {
        // 清场：保证幂等重跑（审计链 append-only，清 audit_log 必须同步重置链头回创世块）
        jdbc.execute("DELETE FROM t_ticket_unit");
        jdbc.execute("DELETE FROM t_approval");
        jdbc.execute("DELETE FROM t_ticket");
        jdbc.execute("DELETE FROM t_audit_log");
        jdbc.execute("UPDATE t_chain_head SET last_seq=0, "
                + "last_hash='0000000000000000000000000000000000000000000000000000000000000000' WHERE id=1");

        // 1. 建单 + 提审（MEDIUM → LEAD 单节点）
        var created = mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"MySQL 冒烟","creatorId":100,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"UPDATE t_demo SET a = 1 WHERE id = 1","dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andReturn();
        long ticketId = extractTicketId(created.getResponse().getContentAsString());

        // 2. 工单真落在 MySQL（不再是内存）
        Long dbCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_ticket WHERE id=?", Long.class, ticketId);
        assertEquals(1L, dbCount, "工单应落 MySQL ticket 表");
        Long unitCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_ticket_unit WHERE ticket_id=?", Long.class, ticketId);
        assertEquals(1L, unitCount, "执行单元应落 MySQL t_ticket_unit 表");

        // 3. 影响面完成 → 提交审批 → 审批通过
        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approverId\":200,\"approve\":true,\"comment\":\"ok\"}"))
                .andExpect(status().isOk());

        // 4. 审批记录落 MySQL
        Long approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_approval WHERE ticket_id=? AND result='APPROVED'", Long.class, ticketId);
        assertTrue(approvalCount >= 1L, "审批记录应落 MySQL");

        // 5. 审计链真库可查 + 完整（状态迁移进链）
        mvc.perform(get("/api/audit-logs/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        Long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Long.class);
        assertTrue(auditCount > 0, "审计事件应落 MySQL audit_log");
    }

    private long extractTicketId(String body) {
        int idx = body.indexOf("\"ticketId\":");
        String rest = body.substring(idx + 11);
        return Long.parseLong(rest.substring(0, rest.indexOf('}')).trim());
    }
}
