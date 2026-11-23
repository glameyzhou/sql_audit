package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 实例状态（生产实例与凭据模型）。
 *
 * <p>对应 {@code t_instance.status} 列（存储码 = 枚举名）。</p>
 */
public enum InstanceStatus implements CodedEnum {
    /** 启用 */
    ACTIVE,
    /** 停用 */
    DISABLED;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static InstanceStatus fromCode(String code) {
        return CodedEnum.fromCode(InstanceStatus.class, code);
    }
}
