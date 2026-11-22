package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.RuleHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险分级 + 审批路由测试（A-2 验收：命中 → 等级 → 审批链映射表驱动；
 * 「配置写错能否把 CRITICAL 降级」反例测试）。
 */
class RiskRouterTest {

    private static RuleHit hit(String code, RuleSeverity severity) {
        return new RuleHit(code, severity, "test");
    }

    // ---------------- 风险分级表驱动（§4.5） ----------------

    @Test
    void riskLevel_tableDriven() {
        // 无命中：DML → MEDIUM；其他 → LOW
        assertEquals(RiskLevel.MEDIUM, RiskAssessor.assess(List.of(), StatementType.UPDATE, false));
        assertEquals(RiskLevel.LOW, RiskAssessor.assess(List.of(), StatementType.SELECT, false));

        // 仅 WARN → MEDIUM
        assertEquals(RiskLevel.MEDIUM,
                RiskAssessor.assess(List.of(hit("R_SELECT_STAR", RuleSeverity.WARN)), StatementType.INSERT, false));

        // 一般 ERROR → HIGH
        assertEquals(RiskLevel.HIGH,
                RiskAssessor.assess(List.of(hit("R_NO_WHERE", RuleSeverity.ERROR)), StatementType.UPDATE, false));

        // CRITICAL 码 → CRITICAL（逐个）
        for (String code : RiskAssessor.CRITICAL_CODES) {
            assertEquals(RiskLevel.CRITICAL,
                    RiskAssessor.assess(List.of(hit(code, RuleSeverity.ERROR)), StatementType.DDL, false),
                    code + " 应定级 CRITICAL");
        }

        // 影响面未知 → 升一级（§4.4）
        assertEquals(RiskLevel.HIGH, RiskAssessor.assess(List.of(), StatementType.UPDATE, true));
        assertEquals(RiskLevel.CRITICAL,
                RiskAssessor.assess(List.of(hit("R_NO_WHERE", RuleSeverity.ERROR)), StatementType.UPDATE, true));
    }

    // ---------------- 审批链映射表驱动 ----------------

    @Test
    void approvalChain_tableDriven() {
        FlowConfig config = new FlowConfig();
        ApprovalRouter router = new ApprovalRouter(config);

        // LOW → 自动通过留痕（空链）
        assertEquals(List.of(), router.route(RiskLevel.LOW, 1, List.of()).nodes());

        // MEDIUM → 主管 1 人
        assertEquals(List.of("LEAD"), router.route(RiskLevel.MEDIUM, 1, List.of()).nodes());

        // HIGH → 主管 + DBA
        assertEquals(List.of("LEAD", "DBA"), router.route(RiskLevel.HIGH, 1, List.of()).nodes());

        // CRITICAL → 主管 + DBA + 管理员 + 二次确认
        ApprovalChain critical = router.route(RiskLevel.CRITICAL, 1, List.of());
        assertEquals(List.of("LEAD", "DBA", "ADMIN"), critical.nodes());
        assertTrue(critical.secondConfirm());
    }

    // ---------------- 反例：配置写错不能把 CRITICAL 降级（A-2 验收） ----------------

    @Test
    void criticalCannotBeDowngraded_byInstanceConfig() {
        FlowConfig config = new FlowConfig();
        // 实例 7 试图把 CRITICAL 降成单节点 LEAD（配置写错/恶意）
        config.addInstanceOverride(7, RiskLevel.CRITICAL,
                new ApprovalChain(List.of("LEAD"), false, "L2_INSTANCE", RiskLevel.CRITICAL));
        ApprovalRouter router = new ApprovalRouter(config);

        // 无 nonOverridable 命中时：弱覆盖被忽略，仍用 L1 的最严链
        ApprovalChain chain = router.route(RiskLevel.CRITICAL, 7, List.of());
        assertEquals(List.of("LEAD", "DBA", "ADMIN"), chain.nodes(), "弱于全局的实例覆盖必须被忽略");
        assertTrue(chain.secondConfirm());

        // 命中 nonOverridable 规则（R_DDL_DESTRUCTIVE）→ 直接锁定 L3，任何配置不可降级
        ApprovalChain locked = router.route(RiskLevel.CRITICAL, 7,
                List.of(hit("R_DDL_DESTRUCTIVE", RuleSeverity.ERROR)));
        assertEquals(List.of("LEAD", "DBA", "ADMIN"), locked.nodes());
        assertTrue(locked.secondConfirm());
        assertEquals("L3_FALLBACK", locked.source());
    }

    @Test
    void instanceOverride_canOnlyTighten() {
        FlowConfig config = new FlowConfig();
        // 加严覆盖：HIGH 从 LEAD+DBA 加到 LEAD+DBA+ADMIN → 生效
        config.addInstanceOverride(7, RiskLevel.HIGH,
                new ApprovalChain(List.of("LEAD", "DBA", "ADMIN"), false, "L2_INSTANCE", RiskLevel.HIGH));
        // 弱覆盖：MEDIUM 从 LEAD 减成空 → 忽略
        config.addInstanceOverride(8, RiskLevel.MEDIUM,
                new ApprovalChain(List.of(), false, "L2_INSTANCE", RiskLevel.MEDIUM));
        ApprovalRouter router = new ApprovalRouter(config);

        assertEquals(List.of("LEAD", "DBA", "ADMIN"), router.route(RiskLevel.HIGH, 7, List.of()).nodes());
        // 实例 8 的弱覆盖被忽略，仍走全局
        assertEquals(List.of("LEAD"), router.route(RiskLevel.MEDIUM, 8, List.of()).nodes());
    }

    @Test
    void criticalAlwaysSecondConfirm_evenIfConfigForgets() {
        FlowConfig config = new FlowConfig();
        // 全局链被错误配置成无二次确认的 CRITICAL 链（模拟配置写错）
        config.globalChain(RiskLevel.CRITICAL).ifPresent(c -> {
            // 不可变配置——直接构造新 router 用覆盖测试
        });
        // 构造忘记二次确认的场景：实例级覆盖 equals 全局但 secondConfirm=false
        config.addInstanceOverride(9, RiskLevel.CRITICAL,
                new ApprovalChain(List.of("LEAD", "DBA", "ADMIN"), false, "L2_INSTANCE", RiskLevel.CRITICAL));
        ApprovalRouter router = new ApprovalRouter(config);
        ApprovalChain chain = router.route(RiskLevel.CRITICAL, 9, List.of());
        assertTrue(chain.secondConfirm(), "CRITICAL 无论配置如何必须带执行前二次确认（§3.4）");
    }
}
