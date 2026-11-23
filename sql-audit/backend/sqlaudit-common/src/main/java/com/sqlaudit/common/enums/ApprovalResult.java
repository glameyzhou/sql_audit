package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批结果（设计文档 §9 approval.result）。
 *
 * <p>原在 sqlaudit-workflow；因存储模型 {@code Approval.result} 需要强类型，下沉到 common。</p>
 *
 * <ul>
 *   <li>{@link #PENDING}：待审批；</li>
 *   <li>{@link #APPROVED}：同意；</li>
 *   <li>{@link #REJECTED}：驳回（工单转 rejected）；</li>
 *   <li>{@link #TRANSFERRED}：转交（B-3 仅保留语义与 DAO 条件更新支持，转交操作由 W2 实现）。</li>
 * </ul>
 */
public enum ApprovalResult implements CodedEnum {
    /** 待审批 */
    PENDING,
    /** 同意 */
    APPROVED,
    /** 驳回（工单转 rejected） */
    REJECTED,
    /** 转交（保留语义，暂未启用） */
    TRANSFERRED;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static ApprovalResult fromCode(String code) {
        return CodedEnum.fromCode(ApprovalResult.class, code);
    }
}
