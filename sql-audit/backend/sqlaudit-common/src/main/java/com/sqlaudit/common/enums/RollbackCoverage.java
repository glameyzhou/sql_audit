package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 回滚覆盖率（设计文档 §5.2 覆盖率矩阵）。
 * <p>对应 {@code rollback_sql.coverage} 列，也是提交预览「可回滚清单」的判定依据。</p>
 * <p>存储码 = 枚举名，见 {@link CodedEnum}。</p>
 */
public enum RollbackCoverage implements CodedEnum {
    /** 完整可回滚：前镜像 / 反向 DDL 覆盖整条语句 */
    FULL,
    /** 部分可回滚 */
    PARTIAL,
    /** 不可回滚（标 not_available） */
    NONE;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RollbackCoverage fromCode(String code) {
        return CodedEnum.fromCode(RollbackCoverage.class, code);
    }
}
