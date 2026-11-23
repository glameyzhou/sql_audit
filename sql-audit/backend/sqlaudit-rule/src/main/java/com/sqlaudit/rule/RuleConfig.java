package com.sqlaudit.rule;

import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;

/**
 * 规则运行时配置（对应 t_rule 覆盖，见 sql-audit-rbac-权限可配置化设计 落地后续）。
 *
 * <p>把「规则启用 / 级别」从硬编码 {@link BuiltInRules} 解耦为可配置：配置源（sqlaudit-server
 * 的 {@code RuleConfigService}）从 t_rule 读取，{@link com.sqlaudit.rule.engine.RuleEngine}
 * 据此跳过停用规则、覆盖级别。nonOverridable 规则不可降级，由引擎守卫。</p>
 */
public interface RuleConfig {

    /** 全启用、不覆盖级别的兜底（无配置源时用）。 */
    RuleConfig DEFAULT = new RuleConfig() {
        @Override
        public boolean isEnabled(RuleCode code) {
            return true;
        }

        @Override
        public RuleSeverity severityOverride(RuleCode code) {
            return null;
        }
    };

    /** 规则是否启用（未配置视为启用）。 */
    boolean isEnabled(RuleCode code);

    /** 生效级别覆盖；返回 {@code null} 表示用内置级别。 */
    RuleSeverity severityOverride(RuleCode code);
}
