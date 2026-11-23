package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.RuleDialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.RuleStatementType;
import java.time.LocalDateTime;

/**
 * 审核规则实体（对应 t_rule 表，设计文档 §6 规则）。
 *
 * <p>唯一写入方：sqlaudit-rule（本轮系统配置 CRUD 经 sqlaudit-server 写入）。
 * {@code params} 为 JSON 文本（阈值等），存储层按 JSON 文本透传，对外序列化为
 * JSON 值（对象/数组/标量）以便前端直接读写。</p>
 */
public class Rule {

    private Long id;
    /** 规则码，如 R_MYSQL_NO_WHERE，唯一 */
    private String code;
    /** 适用方言（{@link RuleDialect#ALL} 表示不限） */
    private RuleDialect dialect;
    /** 适用语句类型（{@link RuleStatementType#ALL} 表示不限） */
    private RuleStatementType statementType;
    /** ERROR 阻断 / WARN 提示 */
    private RuleSeverity severity;
    /** 是否启用 */
    private Boolean enabled;
    /** 阈值等 JSON */
    private Object params;
    private String description;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public RuleDialect getDialect() {
        return dialect;
    }

    public void setDialect(RuleDialect dialect) {
        this.dialect = dialect;
    }

    public RuleStatementType getStatementType() {
        return statementType;
    }

    public void setStatementType(RuleStatementType statementType) {
        this.statementType = statementType;
    }

    public RuleSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RuleSeverity severity) {
        this.severity = severity;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
