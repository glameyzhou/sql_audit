package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审计对象类型（README §4.1，{@code t_audit_log.target_type}）。
 *
 * <p>与 {@link AuditAction} 同一约定：只约束代码里手写的取值，{@code AuditEvent} /
 * {@code AuditLog} 字段仍为 String，落库值取 {@link #getCode()}。</p>
 */
public enum AuditTargetType implements CodedEnum {
    /** 用户 */
    USER("user"),
    /** 角色（RBAC 角色管理） */
    ROLE("role"),
    /** 实例 */
    INSTANCE("instance"),
    /** 审核规则 */
    RULE("rule"),
    /** 审批流规则 */
    FLOW_RULE("flow_rule"),
    /** 工单 */
    TICKET("ticket"),
    /** 执行单元（README §4.1 事件表） */
    TICKET_UNIT("TICKET_UNIT"),
    /** 登录会话（README §4.1 事件表） */
    SESSION("SESSION"),
    /** 回滚主记录 */
    ROLLBACK("rollback"),
    /** 回滚反向语句 */
    ROLLBACK_STATEMENT("rollback_statement"),
    /** 权限点 */
    PERMISSION("permission"),
    /** 表备注（t_table_remark） */
    TABLE_REMARK("table_remark");

    private final String code;

    AuditTargetType(String code) {
        this.code = code;
    }

    /** 审计日志里的存储值。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 存储值 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static AuditTargetType fromCode(String code) {
        return CodedEnum.fromCode(AuditTargetType.class, code);
    }
}
