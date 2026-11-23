package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 反向 SQL 生成器（README §4.4）。
 *
 * <p>对应 {@code rollback_sql.generator} 列（存储码 = 枚举名）。</p>
 *
 * <p>{@link #NONE} 不是生成器：仅用于回滚能力预览里「不可回滚」的计划
 * （coverage=NONE，无反向语句），不会作为可回放记录的 generator 落库。</p>
 */
public enum RollbackGenerator implements CodedEnum {
    /** 前镜像 → 每行一条反向 DML */
    MIRROR,
    /** DDL → 反向 DDL（带有效期与前置条件，README §4.4） */
    REVERSE_DDL,
    /** binlog 闪回 */
    BINLOG,
    /** 快照 / 影子表恢复 */
    SNAPSHOT,
    /** 无生成器（不可回滚的计划占位，README §4.4 能力边界） */
    NONE;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RollbackGenerator fromCode(String code) {
        return CodedEnum.fromCode(RollbackGenerator.class, code);
    }
}
