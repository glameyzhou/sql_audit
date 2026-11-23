package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批结果（对应 {@code t_approval.result} 列）。
 *
 * <ul>
 *   <li>{@link #PENDING}：待审批；</li>
 *   <li>{@link #APPROVED}：同意；</li>
 *   <li>{@link #REJECTED}：驳回（工单转 rejected）；</li>
 *   <li>{@link #TRANSFERRED}：已转交——原记录置本值留痕，同事务插入一条指向代理人的
 *       USER 记录；组判定时忽略本值；</li>
 *   <li>{@link #SKIPPED}：跳过——节点解析出的审批人就是提交人（防自审），
 *       {@code decided_by} 记提交人并写审计；组判定时视为通过。</li>
 * </ul>
 */
public enum ApprovalResult implements CodedEnum {
    /** 待审批 */
    PENDING,
    /** 同意 */
    APPROVED,
    /** 驳回（工单转 rejected） */
    REJECTED,
    /** 已转交（原记录留痕，另起一条指向代理人的 USER 记录） */
    TRANSFERRED,
    /** 跳过（解析到提交人本人，防自审；组判定视为通过） */
    SKIPPED;

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
