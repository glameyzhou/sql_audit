package com.sqlaudit.common.enums;

import java.util.Set;

/**
 * 回滚 SQL 生命周期（设计文档 §5.9）。
 *
 * <pre>
 * generated → verified（语法自检 + 影子库回放 / affected_rows 对账）
 *           → applied / expired / not_available
 * </pre>
 *
 * <p>对应 {@code rollback_sql.status} 列。</p>
 */
public enum RollbackStatus {
    /** 已生成，待校验 */
    GENERATED,
    /** 已通过语法自检与回放校验 */
    VERIFIED,
    /** 已回放执行 */
    APPLIED,
    /** 超过有效期（默认 24h）自动过期 */
    EXPIRED,
    /** 无法生成（覆盖率 NONE 或校验失败） */
    NOT_AVAILABLE;

    /** 是否允许从当前状态迁移到 {@code target}。 */
    public boolean canTransitionTo(RollbackStatus target) {
        return switch (this) {
            case GENERATED -> Set.of(VERIFIED, EXPIRED, NOT_AVAILABLE).contains(target);
            case VERIFIED -> Set.of(APPLIED, EXPIRED).contains(target);
            case APPLIED, EXPIRED, NOT_AVAILABLE -> false; // 终态
        };
    }

    public boolean isTerminal() {
        return this == APPLIED || this == EXPIRED || this == NOT_AVAILABLE;
    }
}
