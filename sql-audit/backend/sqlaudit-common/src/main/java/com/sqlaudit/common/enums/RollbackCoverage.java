package com.sqlaudit.common.enums;

/**
 * 回滚覆盖率（设计文档 §5.2 覆盖率矩阵）。
 * <p>对应 {@code rollback_sql.coverage} 列，也是提交预览「可回滚清单」的判定依据。</p>
 */
public enum RollbackCoverage {
    /** 完整可回滚：前镜像 / 反向 DDL 覆盖整条语句 */
    FULL,
    /** 部分可回滚 */
    PARTIAL,
    /** 不可回滚（标 not_available） */
    NONE
}
