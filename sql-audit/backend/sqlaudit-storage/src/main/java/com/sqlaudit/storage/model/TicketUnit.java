package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnitStatus;
import java.time.LocalDateTime;

/**
 * 执行单元实体（对应 t_ticket_unit 表）。
 *
 * <p>写入边界（状态机）：创建与审批态由 sqlaudit-workflow 写；
 * 执行态（leased/executing/success/failed/needs_confirm）由 sqlaudit-executor 写。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code executedAt} 无唯一约束（同毫秒完成是正常情况）；</li>
 *   <li>{@code statement} 是<strong>明文</strong>语句，落库时由 JDBC DAO 用
 *       {@code SqlCipher} 加密（库里是密文），读出时解密；</li>
 *   <li>{@code statementHash} 按<strong>明文</strong>计算（密文每次加密都不同，不能作指纹）。</li>
 * </ul>
 */
public class TicketUnit {

    private Long id;
    private Long ticketId;
    private Long instanceId;
    private String schemaName;
    private String objectName;
    private Integer unitOrder;
    /** 单条执行语句（明文；DAO 负责加解密） */
    private String statement;
    /** 明文的 SHA-256 hex */
    private String statementHash;
    private StatementType statementType;
    /** pending/leased/executing/success/failed/skipped/needs_confirm，见 UnitStatus */
    private UnitStatus status;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    /** 已预写执行意图（SQL 可能已到生产库） */
    private Boolean intentWritten;
    private LocalDateTime executedAt;
    private Long affectedRows;
    private String ddlFingerprint;
    private String errorMessage;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public Integer getUnitOrder() {
        return unitOrder;
    }

    public void setUnitOrder(Integer unitOrder) {
        this.unitOrder = unitOrder;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getStatementHash() {
        return statementHash;
    }

    public void setStatementHash(String statementHash) {
        this.statementHash = statementHash;
    }

    public StatementType getStatementType() {
        return statementType;
    }

    public void setStatementType(StatementType statementType) {
        this.statementType = statementType;
    }

    public UnitStatus getStatus() {
        return status;
    }

    public void setStatus(UnitStatus status) {
        this.status = status;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Boolean getIntentWritten() {
        return intentWritten;
    }

    public void setIntentWritten(Boolean intentWritten) {
        this.intentWritten = intentWritten;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public Long getAffectedRows() {
        return affectedRows;
    }

    public void setAffectedRows(Long affectedRows) {
        this.affectedRows = affectedRows;
    }

    public String getDdlFingerprint() {
        return ddlFingerprint;
    }

    public void setDdlFingerprint(String ddlFingerprint) {
        this.ddlFingerprint = ddlFingerprint;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
