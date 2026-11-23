package com.sqlaudit.common.model;

import java.util.Objects;

/**
 * SQL 对象引用（README §4.3 ParsedStatement.objects）。
 * <p>对应「目标对象」，用于规则命中、对象白名单、掩码匹配等。</p>
 */
public class SqlObject {
    /** schema 名；IoTDB 存 storage group / database 路径 */
    private String schema;
    /** 对象名（表/视图/索引/时间序列路径） */
    private String name;
    /** 对象类型：TABLE/VIEW/INDEX/SEQUENCE/COLUMN/TIMESERIES 等 */
    private String type;

    public SqlObject() {
    }

    public SqlObject(String schema, String name, String type) {
        this.schema = schema;
        this.name = name;
        this.type = type;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlObject that)) {
            return false;
        }
        return Objects.equals(schema, that.schema)
                && Objects.equals(name, that.name)
                && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, name, type);
    }

    @Override
    public String toString() {
        return (schema == null ? "" : schema + ".") + name;
    }
}
