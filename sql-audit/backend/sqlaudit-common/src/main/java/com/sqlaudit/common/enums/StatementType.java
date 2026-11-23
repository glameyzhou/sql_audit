package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 语句类型（解析器输出统一中间模型的 statementType）。
 * <p>对应 README §4.3 ParsedStatement；落库列为 {@code t_ticket_unit.statement_type}
 * （存储码 = 枚举名），见 {@link CodedEnum}。</p>
 */
public enum StatementType implements CodedEnum {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DDL,
    /** 无法归类 / 解析失败 */
    OTHER;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static StatementType fromCode(String code) {
        return CodedEnum.fromCode(StatementType.class, code);
    }
}
