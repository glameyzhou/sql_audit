package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审核规则适用的方言（README §4.3 规则，{@code t_rule.dialect}）。
 *
 * <p>与 {@link Dialect}（实例实际连的库）不同：这里多一个 {@link #ALL} 表示「不限方言」——
 * 规则配置页从下拉里选，取值收进枚举后拼错会立刻 400，而不是被当自由文本写进库。</p>
 */
public enum RuleDialect implements CodedEnum {
    /** MySQL 8.0 */
    MYSQL,
    /** PostgreSQL 14+ */
    PG,
    /** Apache IoTDB 1.x */
    IOTDB,
    /** 不限方言 */
    ALL;

    /** 存储码（= 枚举名，与 t_rule.dialect / 前端展示一致）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RuleDialect fromCode(String code) {
        return CodedEnum.fromCode(RuleDialect.class, code);
    }
}
