package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * IoTDB SQL 方言（对应 JDBC 的 {@code sql_dialect} 参数，决定连接解析树模型还是表模型 SQL）。
 *
 * <p>IoTDB 2.x 的「树表双模型」是<strong>连接/会话级</strong>开关（默认 tree），表模型（宽表）
 * 须在 JDBC URL 显式带 {@code ?sql_dialect=table}；一条连接只能是一种方言（树方言下
 * {@code SHOW TABLES} 报语法错、表方言下 {@code SHOW DEVICES} 报错）。</p>
 *
 * <p>仅对 {@link Dialect#IOTDB} 实例有意义；非 IOTDB 实例该列为 NULL。NULL 时按 {@link #TREE}
 * （IoTDB 原生默认）处理。</p>
 */
public enum IotdbSqlDialect implements CodedEnum {
    /** 树模型（时序路径 {@code root.db.device.measurement}），IoTDB 默认 */
    TREE,
    /** 表模型（宽表 {@code CREATE TABLE} / {@code information_schema}） */
    TABLE;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static IotdbSqlDialect fromCode(String code) {
        return CodedEnum.fromCode(IotdbSqlDialect.class, code);
    }
}
