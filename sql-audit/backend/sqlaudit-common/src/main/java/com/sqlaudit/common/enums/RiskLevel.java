package com.sqlaudit.common.enums;

/**
 * 风险分级。
 * <p>对应设计文档 §4.5 风险分级 → 审批路由。</p>
 */
public enum RiskLevel {
    /** 只读查询 / 低危且 staging，自动通过留痕 */
    LOW,
    /** 常规 DML、影响行数小于阈值，主管 1 人 */
    MEDIUM,
    /** 高危规则、prod 大表变更、DDL，主管 + DBA */
    HIGH,
    /** 破坏性 DDL、不可回滚变更、跨库批量，主管 + DBA + 管理员 + 执行前二次确认 */
    CRITICAL
}
