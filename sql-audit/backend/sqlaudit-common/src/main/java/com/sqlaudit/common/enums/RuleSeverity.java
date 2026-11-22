package com.sqlaudit.common.enums;

/**
 * 规则严重级别。
 * <p>对应设计文档 §4.3：ERROR 阻断提交，WARN 只提示。</p>
 */
public enum RuleSeverity {
    /** 阻断 */
    ERROR,
    /** 提示 */
    WARN
}
