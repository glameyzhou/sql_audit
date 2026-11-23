package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
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
 *   <li>{@code status} 为 {@link TicketStatus} 枚举，DB 中存其小写存储码（§3.2 状态机），
 *       转换只在 DAO 边界做（{@code getCode()} / {@code fromCode}）；</li>
 *   <li>{@code riskLevel} 为 {@link RiskLevel} 枚举，DB 中存枚举名（LOW/MEDIUM/HIGH/CRITICAL，§4.5）；</li>
 *   <li>{@code instanceIds} 对应 DDL 的 JSON 列（多实例工单），内存模型用 {@code List}，
 *       W2 换 MyBatis 时做 JSON 序列化；</li>
 *   <li>{@code rollbackSummary} 为提交预览时的「可回滚 / 不可回滚」清单（JSON 原样透传，§5.1）；</li>
 *   <li>{@code environment} 为 {@link Environment} 枚举（存储码 prod / test / dev）。</li>
 * </ul>
 */
public class Ticket {

    private Long id;
    private String title;
    private Long creatorId;
    /** 目标实例列表（JSON 列），第一个为主实例 */
    private List<Long> instanceIds;
    /** prod/staging */
    private Environment environment;
    /** LOW/MEDIUM/HIGH/CRITICAL（枚举名） */
    private RiskLevel riskLevel;
    /** draft/submitted/auditing/... （小写，见 TicketStatus） */
    private TicketStatus status;
    private String rollbackSummary;
    /** 工单提交的原始 SQL（工单详情 / 审批视图展示用） */
    private String sqlText;
    /** 分表执行配置（JSON：template/suffixes，审计可见「分表 DDL」语义） */
    private String shardConfig;
    /** 低峰执行窗口 */
    private LocalDateTime scheduledAt;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 是否需要执行前二次确认（§3.4 CRITICAL 链 secondConfirm） */
    private boolean needsSecondConfirm;
    /** 执行前二次确认人（user_id） */
    private Long confirmedBy;
    private LocalDateTime confirmedAt;

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

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public String getRollbackSummary() {
        return rollbackSummary;
    }

    public void setRollbackSummary(String rollbackSummary) {
        this.rollbackSummary = rollbackSummary;
    }

    public String getSqlText() {
        return sqlText;
    }

    public void setSqlText(String sqlText) {
        this.sqlText = sqlText;
    }

    public String getShardConfig() {
        return shardConfig;
    }

    public void setShardConfig(String shardConfig) {
        this.shardConfig = shardConfig;
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

    public boolean isNeedsSecondConfirm() {
        return needsSecondConfirm;
    }

    public void setNeedsSecondConfirm(boolean needsSecondConfirm) {
        this.needsSecondConfirm = needsSecondConfirm;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
