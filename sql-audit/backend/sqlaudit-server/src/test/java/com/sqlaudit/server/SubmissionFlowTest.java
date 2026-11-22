package com.sqlaudit.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交 → 审批端到端集成测试（内存 fake DAO + H2 查询演示库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionFlowTest {

    @Autowired
    private MockMvc mvc;

    // ---------------- 预览 ----------------

    @Test
    void preview_goodUpdate_fullRollbackMediumRisk() throws Exception {
        mvc.perform(post("/api/tickets/preview")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sql":"UPDATE t_user SET a = 1 WHERE id = 1",
                                 "dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitCount").value(1))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.errorBlocked").value(false))
                .andExpect(jsonPath("$.units[0].rollbackPlan.coverage").value("FULL"));
    }

    @Test
    void preview_destructiveDdl_criticalAndSnapshot() throws Exception {
        mvc.perform(post("/api/tickets/preview")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sql":"DROP TABLE t_user",
                                 "dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.units[0].rollbackPlan.coverage").value("NONE"))
                .andExpect(jsonPath("$.units[0].rollbackPlan.generator").value("SNAPSHOT"));
    }

    // ---------------- 建单（ERROR 阻断） ----------------

    @Test
    void submit_errorBlocked_rejectedWithoutTicket() throws Exception {
        mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"无 WHERE 的 UPDATE","creatorId":100,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"UPDATE t_user SET a = 1","dialect":"MYSQL"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERROR_RULE_BLOCKED"));
    }

    // ---------------- 端到端：建单 → 提审批 → 审批通过 ----------------

    @Test
    void endToEnd_createApprove_auditChainIntact() throws Exception {
        // 1. 建单（creator=100）
        MvcResult created = mvc.perform(post("/api/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"常规变更","creatorId":100,
                                 "instanceIds":[1],"environment":"prod",
                                 "sql":"UPDATE t_user SET a = 1 WHERE id = 1","dialect":"MYSQL"}"""))
                .andExpect(status().isOk())
                .andReturn();
        long ticketId = readTicketId(created);

        // 2. 影响面未完成 → 拒绝提交审批（§4.4 硬性要求）
        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPACT_ASSESSMENT_INCOMPLETE"));

        // 3. 影响面完成 → 提交审批（MEDIUM → LEAD 单节点链）
        mvc.perform(post("/api/tickets/{id}/submit-approval", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"impactAssessmentDone\":true}"))
                .andExpect(status().isOk());

        // 4. 防自审：creator 审自己 → 拒绝（§3.4）
        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approverId\":100,\"approve\":true,\"comment\":\"自己批自己\"}"))
                .andExpect(status().isBadRequest());

        // 5. 主管（200）审批通过 → 工单 approved
        mvc.perform(post("/api/approvals/{id}/decide", ticketId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approverId\":200,\"approve\":true,\"comment\":\"同意\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tickets/{id}", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        // 6. 审计链完整（状态迁移 + 审批决策都进链）
        mvc.perform(get("/api/audit-logs/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // ---------------- 查询通道 ----------------

    @Test
    void query_injectsLimitAndAudits() throws Exception {
        // 无 LIMIT 的 SELECT → 平台注入 LIMIT 1000；20 行 demo 数据全返回，不截断
        mvc.perform(post("/api/query")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"instanceId":1,"sql":"SELECT * FROM t_user",
                                 "dialect":"MYSQL","userId":100}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rowCount").value(20))
                .andExpect(jsonPath("$.injected").value(true));

        // 查询进审计链
        mvc.perform(get("/api/audit-logs/verify"))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void query_updateRejectedByGate() throws Exception {
        mvc.perform(post("/api/query")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"instanceId":1,"sql":"UPDATE t_user SET a = 1 WHERE id = 1",
                                 "dialect":"MYSQL","userId":100}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    private long readTicketId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"ticketId\":");
        assertTrue(idx > 0, "响应应含 ticketId: " + body);
        String rest = body.substring(idx + 11);
        int end = rest.indexOf('}');
        return Long.parseLong(rest.substring(0, end).trim());
    }
}
