package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审计结果（设计文档 §7.2，{@code t_audit_log.result}）。
 *
 * <p>与 {@link AuditAction} 同一约定：只约束代码里手写的取值，{@code AuditEvent} /
 * {@code AuditLog} 字段仍为 String，落库值取 {@link #getCode()}。</p>
 *
 * <p>取值是代码里实际写入的四种：{@link #SUCCESS} 成功、{@link #REJECTED} 查询被门禁
 * 拒绝、{@link #FAILED} / {@link #PARTIAL} 回滚回放的两种失败结局。</p>
 */
public enum AuditResult implements CodedEnum {
    /** 成功 */
    SUCCESS("SUCCESS"),
    /** 查询被门禁拒绝 */
    REJECTED("REJECTED"),
    /** 回滚回放失败（0 条应用） */
    FAILED("FAILED"),
    /** 回滚回放部分成功后中断 */
    PARTIAL("PARTIAL");

    private final String code;

    AuditResult(String code) {
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
    public static AuditResult fromCode(String code) {
        return CodedEnum.fromCode(AuditResult.class, code);
    }
}
