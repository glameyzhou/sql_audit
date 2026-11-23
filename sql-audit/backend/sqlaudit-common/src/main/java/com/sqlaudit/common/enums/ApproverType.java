package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批节点审批人类型（对应 {@code t_flow_node.approver_type} / {@code t_approval.approver_type}）。
 *
 * <p>决定 {@code approver_ref} 的解释方式：USER = user_id，ROLE = 角色 code（纯数据，
 * 代码不做字面量判断），INSTANCE_OWNER = 实例负责人（流程节点里 ref 固定 {@code *}，
 * 生成审批记录时解析为具体 user_id 并快照）。</p>
 */
public enum ApproverType implements CodedEnum {
    /** 指定用户：approver_ref = user_id */
    USER,
    /** 按角色解析：approver_ref = 角色 code（t_role.code，纯数据） */
    ROLE,
    /** 实例负责人：节点 ref = *；审批记录 ref = 解析出的 user_id */
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
