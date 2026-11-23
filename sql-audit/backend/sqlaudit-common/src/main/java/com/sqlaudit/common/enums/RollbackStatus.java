package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

/**
 * 回滚 SQL 生命周期（README §4.4）。
 *
 * <pre>
 * generated → verified（语法自检 + 影子库回放 / affected_rows 对账）
 *           → applied / expired / not_available
 * </pre>
 *
 * <p>对应 {@code rollback_sql.status} 列（小写存储码），见 {@link CodedEnum}。</p>
 */
public enum RollbackStatus implements CodedEnum {
    /** 已生成，待校验 */
    GENERATED("generated"),
    /** 已通过语法自检与回放校验 */
    VERIFIED("verified"),
    /** 已回放执行 */
    APPLIED("applied"),
    /** 超过有效期（默认 24h）自动过期 */
    EXPIRED("expired"),
    /** 无法生成（覆盖率 NONE 或校验失败） */
    NOT_AVAILABLE("not_available"),
    /**
     * 回放失败且一条都没应用（执行结局态）。不在 README §4.4 的规划态里，是 RollbackExecutor
     * 回放中断时实际写入的取值，纳入枚举以免库里出现枚举之外的值。
     */
    FAILED("failed"),
    /** 回放中断但已有部分语句应用（执行结局态，同 {@link #FAILED}）；已应用的保留 applied 标记 */
    PARTIAL("partial");

    private final String code;

    RollbackStatus(String code) {
        this.code = code;
    }

    /** DB / JSON 存储码（小写）。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RollbackStatus fromCode(String code) {
        return CodedEnum.fromCode(RollbackStatus.class, code);
    }

    /** 是否允许从当前状态迁移到 {@code target}。 */
    public boolean canTransitionTo(RollbackStatus target) {
        return switch (this) {
            case GENERATED -> Set.of(VERIFIED, EXPIRED, NOT_AVAILABLE, FAILED, PARTIAL).contains(target);
            case VERIFIED -> Set.of(APPLIED, EXPIRED, FAILED, PARTIAL).contains(target);
            case APPLIED, EXPIRED, NOT_AVAILABLE, FAILED, PARTIAL -> false; // 终态
        };
    }

    public boolean isTerminal() {
        return this != GENERATED && this != VERIFIED;
    }
}
