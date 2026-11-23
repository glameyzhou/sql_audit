package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 环境（设计文档 §4 实例 / §5 审批流）。
 *
 * <p>对应 {@code t_instance.environment} / {@code t_ticket.environment} /
 * {@code t_flow_rule.environment} 列。</p>
 *
 * <p>存储码口径不统一，且大小写敏感，各自带码（见 {@link CodedEnum}）：</p>
 * <ul>
 *   <li>实例 / 工单：小写 {@code prod} / {@code test} / {@code dev}；</li>
 *   <li>{@link #STAGING}：历史数据的「预发」，前端已统一按「测试」展示，仅为读旧数据与
 *       旧种子保留，新数据不要再写；</li>
 *   <li>{@link #ALL}：仅审批流规则用，表示「所有环境」的兜底链（V2 种子 L1 全局默认）。</li>
 * </ul>
 */
public enum Environment implements CodedEnum {
    PROD("prod"),
    TEST("test"),
    DEV("dev"),
    /** @deprecated 历史取值（预发），仅兼容存量数据；新数据用 {@link #TEST} */
    STAGING("staging"),
    /** 审批流规则专用：所有环境 */
    ALL("ALL");

    private final String code;

    Environment(String code) {
        this.code = code;
    }

    /** DB / JSON 存储码。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static Environment fromCode(String code) {
        return CodedEnum.fromCode(Environment.class, code);
    }
}
