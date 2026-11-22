package com.sqlaudit.server;

import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Formatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批通过时表结构指纹采集（设计文档 §3.5 审核时记录 ddl_fingerprint）集成测试。
 *
 * <p>memory profile：指纹 provider 为 {@code MemoryStorageConfig#structureFingerprintProvider}
 * 的确定性 stub（{@code SHA-256("stub:" + schema + "." + table)}），断言用同口径期望值。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FingerprintCaptureTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TicketUnitDao ticketUnitDao;

    /**
     * DML 工单（带 schema/object）审批通过 → unit 的 ddl_fingerprint 等于 stub provider 的
     * {@code SHA-256("stub:db1.t_user")}。
     */
    @Test
    void approve_capturesStructureFingerprint_forDmlUnit() throws Exception {
        long ticketId = createTicket("UPDATE db1.t_user SET a = 1 WHERE id = 1");

        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approverId\":200,\"approve\":true,\"comment\":\"同意\"}"))
                .andExpect(status().isOk());

        List<TicketUnit> units = ticketUnitDao.listByTicket(ticketId);
        assertEquals(1, units.size());
        assertEquals(sha256("stub:db1.t_user"), units.get(0).getDdlFingerprint(),
                "审批通过后 DML unit 应已写入表结构指纹");
    }

    /**
     * DDL 工单审批通过 → 跳过采集（对象此刻尚不存在，执行后才有），fingerprint 为 null。
     */
    @Test
    void approve_skipsDdlUnit_fingerprintStaysNull() throws Exception {
        // CREATE TABLE Foo：非破坏性 DDL（大写表名命中 R_TABLE_NAME WARN → MEDIUM 链可审批）
        long ticketId = createTicket("CREATE TABLE Foo (id INT)");

        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approverId\":200,\"approve\":true,\"comment\":\"同意\"}"))
                .andExpect(status().isOk());

        List<TicketUnit> units = ticketUnitDao.listByTicket(ticketId);
        assertEquals(1, units.size());
        assertEquals("DDL", units.get(0).getStatementType());
        assertNull(units.get(0).getDdlFingerprint(),
                "DDL unit 审批时点对象尚不存在，应跳过采集、指纹留空");
    }

    /** 建单（creator=100）并返回 ticketId。 */
    private long createTicket(String sql) throws Exception {
        MvcResult created = mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"指纹采集测试","creatorId":100,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"%s","dialect":"MYSQL"}""".formatted(sql)))
                .andExpect(status().isOk())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        int idx = body.indexOf("\"ticketId\":");
        assertTrue(idx > 0, "响应应含 ticketId: " + body);
        String rest = body.substring(idx + 11);
        int end = rest.indexOf('}');
        return Long.parseLong(rest.substring(0, end).trim());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Formatter f = new Formatter()) {
                for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                    f.format("%02x", b);
                }
                return f.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
