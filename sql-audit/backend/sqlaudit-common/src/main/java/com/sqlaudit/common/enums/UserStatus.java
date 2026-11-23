package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户账号状态（README §4.5 账号与权限）。
 *
 * <p>对应 {@code t_user.status} 列（存储码 = 枚举名）。特权操作每次实时校验，
 * 登录时 {@link #DISABLED} 直接拒绝。</p>
 */
public enum UserStatus implements CodedEnum {
    /** 正常：可登录、可操作 */
    ACTIVE,
    /** 已停用：拒绝登录 */
    DISABLED;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static UserStatus fromCode(String code) {
        return CodedEnum.fromCode(UserStatus.class, code);
    }
}
