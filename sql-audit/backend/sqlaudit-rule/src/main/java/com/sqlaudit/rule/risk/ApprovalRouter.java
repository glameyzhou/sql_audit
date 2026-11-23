package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.model.RuleHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批路由（设计文档 §4.5，修 v1 漏洞的核心实现）。
 *
 * <p><strong>取最严链，不是命中第一条</strong>：收集全局链（L1）与实例覆盖链（L2，
 * 仅当不弱于全局链时生效——实例级只能加严），与 L3 兜底链比较，返回最严的一条。
 * 命中 nonOverridable 规则（R_DDL_DESTRUCTIVE / R_ROLLBACK_UNAVAILABLE /
 * R_PREIMAGE_TOO_LARGE / R_PARSE_FAILED）时，<strong>任何配置都不能把 CRITICAL
 * 降级</strong>——直接锁定 L3。</p>
 */
public class ApprovalRouter {

    private final FlowConfig config;

    public ApprovalRouter(FlowConfig config) {
        this.config = config;
    }

    /**
     * 路由审批链。
     *
     * @param riskLevel  风险等级（RiskAssessor 产出）
     * @param instanceId 目标实例（实例级覆盖用）
     * @param hits       全部规则命中（nonOverridable 检测用；可为空）
     */
    public ApprovalChain route(RiskLevel riskLevel, long instanceId, List<RuleHit> hits) {
        // nonOverridable 规则命中 → 锁定最严链，任何配置不可降级（§4.5）
        if (hits != null && hits.stream().anyMatch(h ->
                h.getCode() != null && RiskAssessor.CRITICAL_CODES.contains(h.getCode()))) {
            return FlowConfig.fallbackStrictest(riskLevel);
        }

        List<ApprovalChain> candidates = new ArrayList<>();
        Map<String, Integer> rank = config.roleRanks();

        // L2 实例级覆盖：只可加严——弱于全局链的覆盖忽略
        Optional<ApprovalChain> l2 = config.instanceChain(instanceId, riskLevel);
        Optional<ApprovalChain> l1 = config.globalChain(riskLevel);
        if (l2.isPresent() && l1.isPresent()) {
            if (l2.get().atLeastAsStrictAs(l1.get(), rank)) {
                candidates.add(l2.get());
            }
            // 弱于全局 → 忽略（实例级只能加严，§8.2）
        } else if (l2.isPresent()) {
            candidates.add(l2.get());
        }
        l1.ifPresent(candidates::add);

        // 无任何配置（理论上 L1 恒有）→ L3 兜底，绝不无审批
        if (candidates.isEmpty()) {
            return FlowConfig.fallbackStrictest(riskLevel);
        }

        // 取最严：strictness 最大者；平手取 L3 语义（secondConfirm 优先）
        ApprovalChain strictest = candidates.get(0);
        for (ApprovalChain c : candidates) {
            if (c.strictness(rank) > strictest.strictness(rank)
                    || c.strictness(rank) == strictest.strictness(rank) && c.secondConfirm()) {
                strictest = c;
            }
        }
        // CRITICAL 无论配置如何都必须带二次确认（§3.4）
        if (riskLevel == RiskLevel.CRITICAL && !strictest.secondConfirm()) {
            strictest = new ApprovalChain(strictest.nodes(), true,
                    strictest.source() + "+CRITICAL_SECOND_CONFIRM", riskLevel);
        }
        return strictest;
    }

    /** 角色权重表（多实例取最严时复用，见 TicketService）。 */
    public Map<String, Integer> roleRanks() {
        return config.roleRanks();
    }
}
