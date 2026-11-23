package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 规则严重级别。
 * <p>对应 README §4.3：ERROR 阻断提交，WARN 只提示。</p>
 * <p>对应 {@code t_rule.severity} 列（存储码 = 枚举名），见 {@link CodedEnum}。</p>
 */
public enum RuleSeverity implements CodedEnum {
    /** 阻断 */
    ERROR,
    /** 提示 */
    WARN;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RuleSeverity fromCode(String code) {
        return CodedEnum.fromCode(RuleSeverity.class, code);
    }
}
