package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.RuleHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险分级 + 审批路由测试（追加模式，决定 9：生效链 = 全局链 + 实例链，
 * 实例组 node_order 接在全局组之后重新编号；secondConfirm 恒由 CRITICAL 推导）。
 */
class RiskRouterTest {

    private static RuleHit hit(RuleCode code, RuleSeverity severity) {
        return new RuleHit(code, severity, "test");
    }

    private static ChainNode roleNode(int order, long flowNodeId, String roleCode) {
        return new ChainNode(order, flowNodeId, ApproverType.ROLE, roleCode, ApprovalMode.OR);
    }

    private static ChainNode userNode(int order, long flowNodeId, long userId) {
        return new ChainNode(order, flowNodeId, ApproverType.USER, String.valueOf(userId),
                ApprovalMode.OR);
    }

    // ---------------- 风险分级表驱动（README §4.3） ----------------

    @Test
    void riskLevel_tableDriven() {
        // 无命中：DML → MEDIUM；其他 → LOW
        assertEquals(RiskLevel.MEDIUM, RiskAssessor.assess(List.of(), StatementType.UPDATE, false));
        assertEquals(RiskLevel.LOW, RiskAssessor.assess(List.of(), StatementType.SELECT, false));

        // 仅 WARN → MEDIUM
        assertEquals(RiskLevel.MEDIUM,
                RiskAssessor.assess(List.of(hit(RuleCode.R_SELECT_STAR, RuleSeverity.WARN)), StatementType.INSERT, false));

        // 一般 ERROR → HIGH
        assertEquals(RiskLevel.HIGH,
                RiskAssessor.assess(List.of(hit(RuleCode.R_NO_WHERE, RuleSeverity.ERROR)), StatementType.UPDATE, false));

        // CRITICAL 码 → CRITICAL（逐个）
        for (RuleCode code : RiskAssessor.CRITICAL_CODES) {
            assertEquals(RiskLevel.CRITICAL,
                    RiskAssessor.assess(List.of(hit(code, RuleSeverity.ERROR)), StatementType.DDL, false),
                    code + " 应定级 CRITICAL");
        }

        // 影响面未知 → 升一级（README §4.3）
        assertEquals(RiskLevel.HIGH, RiskAssessor.assess(List.of(), StatementType.UPDATE, true));
        assertEquals(RiskLevel.CRITICAL,
                RiskAssessor.assess(List.of(hit(RuleCode.R_NO_WHERE, RuleSeverity.ERROR)), StatementType.UPDATE, true));
    }

    // ---------------- 空配置（无 DB 加载）→ 全空链 ----------------

    @Test
    void emptyConfig_allChainsEmpty() {
        ApprovalRouter router = new ApprovalRouter(new FlowConfig());

        assertTrue(router.route(RiskLevel.LOW, 1).isEmpty());
        assertTrue(router.route(RiskLevel.MEDIUM, 1).isEmpty());
        assertTrue(router.route(RiskLevel.HIGH, 1).isEmpty());
        assertTrue(router.route(RiskLevel.CRITICAL, 1).isEmpty());
    }

    // ---------------- 全局链（决定 9：追加模式，全局组在前） ----------------

    @Test
    void globalOnly_chainOrderPreserved() {
        FlowConfig config = new FlowConfig();
        config.putGlobal(RiskLevel.HIGH,
                List.of(roleNode(1, 11, "LEAD"), roleNode(2, 12, "DBA")));
        ApprovalRouter router = new ApprovalRouter(config);

        ApprovalChain chain = router.route(RiskLevel.HIGH, 1);
        assertEquals(2, chain.nodes().size());
        assertEquals("LEAD", chain.nodes().get(0).ref());
        assertEquals(1, chain.nodes().get(0).nodeOrder());
        assertEquals(11L, chain.nodes().get(0).flowNodeId());
        assertEquals("DBA", chain.nodes().get(1).ref());
        assertEquals(2, chain.nodes().get(1).nodeOrder());
        assertFalse(chain.secondConfirm());
    }

    // ---------------- 实例链追加：排在全局之后并重新编号 ----------------

    @Test
    void instanceChain_appendedAfterGlobal_renumbered() {
        FlowConfig config = new FlowConfig();
        // 全局 HIGH：1=LEAD、2=DBA
        config.putGlobal(RiskLevel.HIGH,
                List.of(roleNode(1, 11, "LEAD"), roleNode(2, 12, "DBA")));
        // 实例 7 专用：1=USER 张三、2=USER 李四 → 生效链组序应为 3、4
        config.putInstance(7, RiskLevel.HIGH,
                List.of(userNode(1, 21, 100L), userNode(2, 22, 200L)));
        ApprovalRouter router = new ApprovalRouter(config);

        ApprovalChain chain = router.route(RiskLevel.HIGH, 7);
        assertEquals(4, chain.nodes().size(), "生效链 = 全局组 + 实例组");
        assertEquals(3, chain.nodes().get(2).nodeOrder(), "实例组 1 接在全局组后：组序 3");
        assertEquals(21L, chain.nodes().get(2).flowNodeId(), "flowNodeId 保留真实来源");
        assertEquals("100", chain.nodes().get(2).ref());
        assertEquals(4, chain.nodes().get(3).nodeOrder());
        assertEquals("200", chain.nodes().get(3).ref());
    }

    @Test
    void instanceChain_onlyInstance_keepsOriginalOrder() {
        FlowConfig config = new FlowConfig();
        config.putInstance(7, RiskLevel.MEDIUM,
                List.of(roleNode(1, 21, "LEAD"), roleNode(2, 22, "DBA")));
        ApprovalRouter router = new ApprovalRouter(config);

        ApprovalChain chain = router.route(RiskLevel.MEDIUM, 7);
        assertEquals(2, chain.nodes().size());
        assertEquals(1, chain.nodes().get(0).nodeOrder());
        assertEquals(2, chain.nodes().get(1).nodeOrder());
    }

    @Test
    void instanceChain_otherInstanceNotLeaked() {
        FlowConfig config = new FlowConfig();
        config.putGlobal(RiskLevel.MEDIUM, List.of(roleNode(1, 11, "LEAD")));
        config.putInstance(7, RiskLevel.MEDIUM, List.of(roleNode(1, 21, "DBA")));
        ApprovalRouter router = new ApprovalRouter(config);

        // 实例 8 无专用链 → 只有全局链
        ApprovalChain chain = router.route(RiskLevel.MEDIUM, 8);
        assertEquals(1, chain.nodes().size());
        assertEquals("LEAD", chain.nodes().get(0).ref());
    }

    // ---------------- secondConfirm 恒由 CRITICAL 推导（README §4.6） ----------------

    @Test
    void secondConfirm_derivedFromCriticalOnly() {
        FlowConfig config = new FlowConfig();
        config.putGlobal(RiskLevel.CRITICAL, List.of(roleNode(1, 11, "LEAD")));
        config.putGlobal(RiskLevel.HIGH, List.of(roleNode(1, 12, "DBA")));
        ApprovalRouter router = new ApprovalRouter(config);

        assertTrue(router.route(RiskLevel.CRITICAL, 1).secondConfirm(),
                "CRITICAL 无论配置如何必须带执行前二次确认");
        assertFalse(router.route(RiskLevel.HIGH, 1).secondConfirm());
        assertFalse(router.route(RiskLevel.LOW, 1).secondConfirm());

        // 空配置下 CRITICAL 同样带二次确认
        assertTrue(new ApprovalRouter(new FlowConfig()).route(RiskLevel.CRITICAL, 1).secondConfirm());
    }
}
