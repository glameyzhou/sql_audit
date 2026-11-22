package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 审批流规则实体（对应 t_flow_rule 表，设计文档 §5 审批流）。
 *
 * <p>唯一写入方：sqlaudit-workflow（本轮系统配置 CRUD 经 sqlaudit-server 写入）。
 * {@code instanceId} 为 null 表示全局默认链（L1），非空为实例级覆盖链（L2）。</p>
 */
public class FlowRule {

    private Long id;
    private String name;
    /** prod / staging / ALL */
    private String environment;
    /** NULL=全局默认 L1；非空=实例级覆盖 L2 */
    private Long instanceId;
    /** LOW / MEDIUM / HIGH / CRITICAL */
    private String riskLevel;
    /** CRITICAL 不可被任何配置降级 */
    private Boolean nonOverridable;
    /** 同 risk_level 多条时取 priority 最高 */
    private Integer priority;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Boolean getNonOverridable() {
        return nonOverridable;
    }

    public void setNonOverridable(Boolean nonOverridable) {
        this.nonOverridable = nonOverridable;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
