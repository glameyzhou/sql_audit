package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执行前门禁编码（README §4.2，{@code ExecutionGate.GateResult.failedGate}）。
 *
 * <p>门禁未通过时写进执行单元的 error_message（形如 {@code R_SCHEMA_DRIFT: 表结构与审核时不一致…}），
 * worker 侧按门禁做补偿（{@link #APPROVAL_TTL} 超时把工单置 expired）。取值沿用历史口径：
 * 结构漂移与回滚能力两道门直接借用对应规则码，审批时效是独立的门禁名。</p>
 */
public enum GateCode implements CodedEnum {
    /** 审批已过有效期（approved → 执行最长 24h），需重新审批 */
    APPROVAL_TTL("APPROVAL_TTL"),
    /** 审核后表结构被改动（DDL 指纹不一致），退回重审 */
    SCHEMA_DRIFT("R_SCHEMA_DRIFT"),
    /** 回滚能力较审核时降级，按策略阻断 */
    ROLLBACK_UNAVAILABLE("R_ROLLBACK_UNAVAILABLE");

    private final String code;

    GateCode(String code) {
        this.code = code;
    }

    /** 门禁码（error_message 前缀 / 审计里的取值）。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 门禁码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static GateCode fromCode(String code) {
        return CodedEnum.fromCode(GateCode.class, code);
    }
}
