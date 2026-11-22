package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批流配置（设计文档 §4.5 / §8.2，对应 flow_rule / flow_node 的内存模型）。
 *
 * <p>三层结构：</p>
 * <ul>
 *   <li><strong>L1 全局默认</strong>：按风险等级的兜底链（新接入的库漏配时落到这里，
 *       绝不出现「无审批」）；</li>
 *   <li><strong>L2 实例级覆盖</strong>：只能加严（链强度 ≥ 同等级全局链才生效，
 *       弱于全局的覆盖直接忽略并标记）；</li>
 *   <li><strong>L3 最严链兜底</strong>：CRITICAL 级 + nonOverridable 规则命中时
 *       的不可降级链。</li>
 * </ul>
 */
public class FlowConfig {

    private final Map<RiskLevel, ApprovalChain> global = new EnumMap<>(RiskLevel.class);

    /** 实例级覆盖：instanceId → (riskLevel → chain)。 */
    private final Map<Long, Map<RiskLevel, ApprovalChain>> instanceOverrides = new java.util.HashMap<>();

    public FlowConfig() {
        // L1 全局默认（§4.5 表）
        global.put(RiskLevel.LOW, new ApprovalChain(List.of(), false, "L1_GLOBAL", RiskLevel.LOW));
        global.put(RiskLevel.MEDIUM, new ApprovalChain(List.of("LEAD"), false, "L1_GLOBAL", RiskLevel.MEDIUM));
        global.put(RiskLevel.HIGH, new ApprovalChain(List.of("LEAD", "DBA"), false, "L1_GLOBAL", RiskLevel.HIGH));
        global.put(RiskLevel.CRITICAL, new ApprovalChain(List.of("LEAD", "DBA", "ADMIN"), true,
                "L1_GLOBAL", RiskLevel.CRITICAL));
    }

    /** 配置实例级覆盖（只可加严由 {@link ApprovalRouter} 守卫；此处仅登记）。 */
    public void addInstanceOverride(long instanceId, RiskLevel level, ApprovalChain chain) {
        instanceOverrides.computeIfAbsent(instanceId, k -> new EnumMap<>(RiskLevel.class))
                .put(level, chain);
    }

    public Optional<ApprovalChain> globalChain(RiskLevel level) {
        return Optional.ofNullable(global.get(level));
    }

    public Optional<ApprovalChain> instanceChain(long instanceId, RiskLevel level) {
        return Optional.ofNullable(instanceOverrides.getOrDefault(instanceId, Map.of()).get(level));
    }

    /** L3 最严链兜底（§4.5：CRITICAL 不可被任何配置降级）。 */
    public static ApprovalChain fallbackStrictest(RiskLevel level) {
        return new ApprovalChain(List.of("LEAD", "DBA", "ADMIN"), true, "L3_FALLBACK", level);
    }
}
