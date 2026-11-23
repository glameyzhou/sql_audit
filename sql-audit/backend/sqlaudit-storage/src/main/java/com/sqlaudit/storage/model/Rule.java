package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.RuleDialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.RuleStatementType;
import java.time.LocalDateTime;

/**
 * 审核规则实体（对应 t_rule 表）。
 *
 * <p>唯一写入方：sqlaudit-server。{@code params} 为 JSON 文本（阈值等）：DDL 里是
 * {@code TEXT} 列，DAO 用 {@code setString} / {@code getString} <strong>原样读写</strong>，
 * 不经过任何方言 JSON 类型（PG 的 jsonb 会规范化空白、重排 key，无法字节级透传）。
 * 模型层保持 String，是否解析成 JSON 值由上层决定。</p>
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
    /** 阈值等 JSON 文本（原样透传，不解析） */
    private String params;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
