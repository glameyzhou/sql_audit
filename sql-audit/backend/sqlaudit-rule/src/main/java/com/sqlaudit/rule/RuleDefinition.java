package com.sqlaudit.rule;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;

import java.util.Collections;
import java.util.Map;

/**
 * 规则定义（设计文档 §4.3 规则模型，对应 rule 表的静态内置部分）。
 *
 * <p>首版 27 条内置规则按方言分组（§4.3 表）。配置化（severity 覆盖、阈值 params、
 * 按实例启停）属 W2 的 rule 表读写，本层先提供静态定义 + 不可降级标记。</p>
 *
 * @param code          规则编码（R_NO_WHERE 等）
 * @param dialect       适用方言（ALL=全部）
 * @param statementType 适用语句类型（ALL=全部）
 * @param severity      级别：ERROR 阻断 / WARN 提示
 * @param nonOverridable 任何配置都不能降级（§4.5：CRITICAL 规则不可覆盖）
 * @param params        阈值等（不可变）
 * @param description   描述
 */
public record RuleDefinition(
        String code,
        Dialect dialect,
        StatementType statementType,
        RuleSeverity severity,
        boolean nonOverridable,
        Map<String, Object> params,
        String description) {

    public RuleDefinition {
        params = params == null ? Map.of() : Collections.unmodifiableMap(params);
    }

    /** 长整型阈值参数（缺省取 defaultVal）。 */
    public long longParam(String key, long defaultVal) {
        Object v = params.get(key);
        return v instanceof Number n ? n.longValue() : defaultVal;
    }

    /** 是否适用于给定方言（dialect=null 表示全适用）。 */
    public boolean matchesDialect(Dialect d) {
        return dialect == null || dialect == d;
    }

    /** 是否适用于给定语句类型（statementType=null 表示全适用）。 */
    public boolean matchesStatementType(StatementType t) {
        return statementType == null || statementType == t;
    }
}
