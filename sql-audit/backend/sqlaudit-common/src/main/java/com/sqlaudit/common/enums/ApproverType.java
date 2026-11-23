package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批节点审批人类型（设计文档 §8.2 flow_node.approver_type）。
 *
 * <p>决定 {@code approver_ref} 的解释方式：USER = user_id，ROLE = 角色 code，
 * INSTANCE_OWNER = 实例 Owner（ref 固定 {@code *}）。</p>
 */
public enum ApproverType implements CodedEnum {
    /** 指定用户：approver_ref = user_id */
    USER,
    /** 按角色解析：approver_ref = 角色 code（DEV/LEAD/DBA/ADMIN） */
    ROLE,
    /** 实例 Owner：approver_ref = * */
    INSTANCE_OWNER;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static ApproverType fromCode(String code) {
        return CodedEnum.fromCode(ApproverType.class, code);
    }
}
