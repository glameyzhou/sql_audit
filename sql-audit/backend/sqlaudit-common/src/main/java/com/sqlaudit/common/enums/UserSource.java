package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户账号来源（设计文档 §8 账号与权限）。
 *
 * <p>对应 {@code t_user.source} 列（存储码 = 枚举名）。企微账号走 OAuth 免密，
 * 本地应急账号走 bcrypt 密码 + 可选 TOTP。</p>
 */
public enum UserSource implements CodedEnum {
    /** 企微账号：wework_openid 非空 */
    WEWORK,
    /** 本地应急账号：password_hash 非空 */
    LOCAL;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static UserSource fromCode(String code) {
        return CodedEnum.fromCode(UserSource.class, code);
    }
}
