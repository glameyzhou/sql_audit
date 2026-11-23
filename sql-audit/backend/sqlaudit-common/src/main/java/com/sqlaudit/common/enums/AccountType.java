package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 生产库账号类型（生产实例与凭据模型）。
 *
 * <p>对应 {@code t_credential.account_type} 列（存储码 = 枚举名）。三类生产账号物理隔离：
 * {@code (instance_id, account_type)} 唯一，一个实例每种类型最多一份凭据。</p>
 */
public enum AccountType implements CodedEnum {
    /** 只读元数据账号：库表结构、SHOW CREATE TABLE（结构指纹） */
    RO_META,
    /** 只读数据账号：查询通道 */
    RO_DATA,
    /** 执行账号：工单执行与回滚回放 */
    RW_EXEC;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static AccountType fromCode(String code) {
        return CodedEnum.fromCode(AccountType.class, code);
    }
}
