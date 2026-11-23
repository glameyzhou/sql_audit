package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 风险分级。
 * <p>对应设计文档 §4.5 风险分级 → 审批路由。</p>
 * <p>对应 {@code t_ticket.risk_level} / {@code t_flow_rule.risk_level} 列（存储码 = 枚举名），
 * 见 {@link CodedEnum}。</p>
 */
public enum RiskLevel implements CodedEnum {
    /** 只读查询 / 低危且 staging，自动通过留痕 */
    LOW,
    /** 常规 DML、影响行数小于阈值，主管 1 人 */
    MEDIUM,
    /** 高危规则、prod 大表变更、DDL，主管 + DBA */
    HIGH,
    /** 破坏性 DDL、不可回滚变更、跨库批量，主管 + DBA + 管理员 + 执行前二次确认 */
    CRITICAL;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RiskLevel fromCode(String code) {
        return CodedEnum.fromCode(RiskLevel.class, code);
    }
}
