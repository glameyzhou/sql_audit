package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.RiskLevel;
import java.time.LocalDateTime;

/**
 * 审批流程实体（对应 t_flow_rule 表）——「范围 × 风险等级」一条，至多一条。
 *
 * <p>唯一写入方：sqlaudit-server。{@code instanceId} 为 null 表示全局流程，非空表示
 * 该实例的专用流程。生效链 = 全局链节点组 + 实例链节点组（实例组排在后面），
 * 所以实例配置只会让审批更严、不会更松——不再有「链强度比较」那套逻辑。</p>
 */
public class FlowRule {

    private Long id;
    private String name;
    /** NULL=全局流程；非空=该实例的专用流程 */
    private Long instanceId;
    /** LOW / MEDIUM / HIGH / CRITICAL */
    private RiskLevel riskLevel;
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

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
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
