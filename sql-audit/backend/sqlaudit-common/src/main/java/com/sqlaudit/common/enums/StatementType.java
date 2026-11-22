package com.sqlaudit.common.enums;

/**
 * 语句类型（解析器输出统一中间模型的 statementType）。
 * <p>对应设计文档 §4.1 ParsedStatement。</p>
 */
public enum StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DDL,
    /** 无法归类 / 解析失败 */
    OTHER
}
