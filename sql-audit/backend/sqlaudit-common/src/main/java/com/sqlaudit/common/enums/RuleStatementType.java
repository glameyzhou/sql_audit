package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审核规则适用的语句类型（README §4.3 规则，{@code t_rule.statement_type}）。
 *
 * <p>与 {@link StatementType}（解析器对单条语句的归类）不同：这里多一个 {@link #ALL} 表示
 * 「不限语句类型」，且没有 {@code OTHER}（无法归类的语句不会被规则表按类型选中）。
 * 规则配置页从下拉里选，取值收进枚举后拼错会立刻 400。</p>
 */
public enum RuleStatementType implements CodedEnum {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DDL,
    /** 不限语句类型 */
    ALL;

    /** 存储码（= 枚举名，与 t_rule.statement_type / 前端展示一致）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RuleStatementType fromCode(String code) {
        return CodedEnum.fromCode(RuleStatementType.class, code);
    }
}
