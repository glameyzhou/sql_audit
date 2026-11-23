package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * SQL 方言。
 * <p>对应设计文档 §2.2 / §4.1，三类生产库账号物理隔离。</p>
 * <p>对应 {@code t_instance.dialect} 列（存储码 = 枚举名），见 {@link CodedEnum}。</p>
 */
public enum Dialect implements CodedEnum {
    /** MySQL 8.0 */
    MYSQL,
    /** PostgreSQL 14+ */
    PG,
    /** Apache IoTDB 1.x */
    IOTDB;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static Dialect fromCode(String code) {
        return CodedEnum.fromCode(Dialect.class, code);
    }
}
