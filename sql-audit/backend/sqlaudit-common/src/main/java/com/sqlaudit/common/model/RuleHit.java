package com.sqlaudit.common.model;

import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则命中结果（设计文档 §4.3）。
 *
 * <p>对应 {@code audit_result(unit_id, rule_code, level, message, extra)}。</p>
 *
 * <p>契约要点（§4.3）：</p>
 * <ul>
 *   <li>ERROR 阻断提交，WARN 只提示；</li>
 *   <li>豁免必须走审批并写审计；</li>
 *   <li>审批路由取所有命中规则中最严的一条，不是命中第一条。</li>
 * </ul>
 */
public class RuleHit {

    /** 命中的内置规则 */
    private RuleCode code;

    private RuleSeverity severity;

    private String message;

    /** 附加信息：阈值、命中的列/对象、影响行数等 */
    private Map<String, Object> extra = new LinkedHashMap<>();

    public RuleHit() {
    }

    public RuleHit(RuleCode code, RuleSeverity severity, String message) {
        this.code = code;
        this.severity = severity;
        this.message = message;
    }

    public RuleCode getCode() {
        return code;
    }

    public void setCode(RuleCode code) {
        this.code = code;
    }

    public RuleSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RuleSeverity severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public RuleHit withExtra(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }
}
