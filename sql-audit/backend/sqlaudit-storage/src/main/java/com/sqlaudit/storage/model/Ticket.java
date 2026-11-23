package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import java.time.LocalDateTime;

/**
 * 工单实体（对应 t_ticket 表）。
 *
 * <p>写入边界：创建与审批态（draft/submitted/auditing/approved/...）由 sqlaudit-workflow 写；
 * 执行态由 sqlaudit-executor 写，两者通过状态机边界隔离。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code status} 为 {@link TicketStatus} 枚举，DB 中存其小写存储码，
 *       转换只在 DAO 边界做（{@code getCode()} / {@code fromCode}）；</li>
 *   <li>{@code riskLevel} 为 {@link RiskLevel} 枚举，DB 中存枚举名（LOW/MEDIUM/HIGH/CRITICAL）；</li>
 *   <li>{@code instanceId} 是<strong>单个</strong>目标实例（多实例工单已收窄为单实例）：
 *       审批链按它路由，执行单元也绑它，不再有「多实例取最严」那套逻辑；</li>
 *   <li>{@code rollbackSummary} 为提交预览时的「可回滚 / 不可回滚」清单（JSON 文本，原样透传）；</li>
 *   <li>{@code sqlText} 是<strong>明文</strong>原始 SQL，落库时由 JDBC DAO 用
 *       {@code SqlCipher} 加密（库里是密文），读出时解密。</li>
 * </ul>
 */
public class Ticket {

    private Long id;
    private String title;
    private Long creatorId;
    /** 目标实例（单实例工单） */
    private Long instanceId;
    /** LOW/MEDIUM/HIGH/CRITICAL（枚举名） */
    private RiskLevel riskLevel;
    /** draft/submitted/auditing/... （小写，见 TicketStatus） */
    private TicketStatus status;
    private String rollbackSummary;
    /** 工单提交的原始 SQL（明文；DAO 负责加解密） */
    private String sqlText;
    /** 分表执行配置（JSON 文本：template/suffixes，审计可见「分表 DDL」语义） */
    private String shardConfig;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 是否需要执行前二次确认（README §4.6 CRITICAL 链 secondConfirm） */
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
