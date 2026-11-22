package com.sqlaudit.storage.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单实体（对应 ticket 表，设计文档 §3.2 / §9）。
 *
 * <p>写入边界（DDL 注释）：创建与审批态（draft/submitted/auditing/approved/...）由
 * sqlaudit-workflow 写；执行态由 sqlaudit-executor 写，两者通过状态机边界隔离。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code status} 为小写字符串（§3.2 状态机），与 {@link TicketUnit#getStatus()} 风格一致；</li>
 *   <li>{@code riskLevel} 存枚举名（LOW/MEDIUM/HIGH/CRITICAL，§4.5）；</li>
 *   <li>{@code instanceIds} 对应 DDL 的 JSON 列（多实例工单），内存模型用 {@code List}，
 *       W2 换 MyBatis 时做 JSON 序列化；</li>
 *   <li>{@code rollbackSummary} 为提交预览时的「可回滚 / 不可回滚」清单（JSON 原样透传，§5.1）。</li>
 * </ul>
 */
public class Ticket {

    private Long id;
    private String title;
    private Long creatorId;
    /** 目标实例列表（JSON 列），第一个为主实例 */
    private List<Long> instanceIds;
    /** prod/staging */
    private String environment;
    /** LOW/MEDIUM/HIGH/CRITICAL（枚举名） */
    private String riskLevel;
    /** draft/submitted/auditing/... （小写，见 TicketStatus） */
    private String status;
    private String rollbackSummary;
    /** 低峰执行窗口 */
    private LocalDateTime scheduledAt;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public List<Long> getInstanceIds() {
        return instanceIds;
    }

    public void setInstanceIds(List<Long> instanceIds) {
        this.instanceIds = instanceIds;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRollbackSummary() {
        return rollbackSummary;
    }

    public void setRollbackSummary(String rollbackSummary) {
        this.rollbackSummary = rollbackSummary;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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
