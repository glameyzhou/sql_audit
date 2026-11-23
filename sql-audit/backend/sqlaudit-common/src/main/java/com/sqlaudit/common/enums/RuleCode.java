package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 内置审核规则编码（README §4.3，首版 26 条；V2 种子 {@code t_rule.code}）。
 *
 * <p>用途：<strong>代码里引用规则</strong>（规则定义、规则命中 {@code RuleHit.code}、按规则码
 * 分级的 RiskAssessor、把命中转译为不可回滚原因的回滚能力评估、执行前门禁）一律用本枚举，
 * 不再手写 {@code "R_NO_PK"} 这类字面量——规则码拼错不会报错，只会让分级 / 回滚判定静默走偏。</p>
 *
 * <p>边界：{@code t_rule.code} 是数据列（规则编辑页可自由填写），规则实体与配置表里仍按字符串
 * 存取；与内置规则比对时用 {@link #getCode()}。新增内置规则时在此追加，并同步
 * {@code BuiltInRules.ALL} 与 V2 种子。</p>
 */
public enum RuleCode implements CodedEnum {
    R_AFFECT_TOO_MANY,
    R_BIG_UNIT_COUNT,
    R_DDL_DESTRUCTIVE,
    R_DDL_NO_IF_EXISTS,
    R_FULL_TABLE_SCAN,
    R_IOTDB_DROP,
    R_IOTDB_NO_TIME_RANGE,
    R_IOTDB_TTL,
    R_INDEX_NAME,
    R_LIMIT_ORDER_DML,
    R_LOW_PEAK_WINDOW,
    R_MULTI_TABLE_DML,
    R_NO_LIMIT_QUERY,
    R_NO_PK,
    R_NO_WHERE,
    R_NONDETERMINISTIC_SET,
    R_OBJECT_NOT_ALLOWED,
    R_PARSE_FAILED,
    R_PREIMAGE_TOO_LARGE,
    R_ROLLBACK_UNAVAILABLE,
    /** 执行期规则（README §4.2）：提交期不触发，worker 执行前比对结构指纹 */
    R_SCHEMA_DRIFT,
    R_SELECT_STAR,
    R_SENSITIVE_COLUMN,
    R_SQL_INJECTION_PATTERN,
    R_TABLE_NAME,
    R_TX_DDL_MIX;

    /** 规则码（= 枚举名，与 t_rule.code / 前端展示一致）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 规则码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RuleCode fromCode(String code) {
        return CodedEnum.fromCode(RuleCode.class, code);
    }
}
