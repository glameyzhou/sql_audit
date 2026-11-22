package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 执行单元实体（对应 ticket_unit 表，设计文档 §3.3 / §9）。
 *
 * <p>写入边界（状态机 §3.2）：创建与审批态由 sqlaudit-workflow 写；
 * 执行态（leased/executing/success/failed/needs_confirm）由 sqlaudit-executor 写。</p>
 *
 * <p>关键字段约定：</p>
 * <ul>
 *   <li>{@code executed_at} 无唯一约束（同毫秒完成是正常情况）；</li>
 *   <li>{@code exec_fingerprint} 普通索引，用于跨工单重复提交检测，非幂等手段；</li>
 *   <li>{@code statement} 为 AES-GCM 密文（VARBINARY）。</li>
 * </ul>
 */
public class TicketUnit {

    private Long id;
    private Long ticketId;
    private Long instanceId;
    private String schemaName;
    private String objectName;
    private Integer unitOrder;
    /** 加密后的语句（VARBINARY） */
    private byte[] statement;
    private String statementHash;
    private String statementType;
    /** IoTDB 等方言扩展（JSON） */
    private String dialectSpecific;
    /** pending/leased/executing/success/failed/skipped/needs_confirm，见 UnitStatus */
    private String status;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    /** 已预写执行意图（SQL 可能已到生产库） */
    private Boolean intentWritten;
    private LocalDateTime executedAt;
    private String execFingerprint;
    private Long preimageRows;
    private Long affectedRows;
    private String ddlFingerprint;
    private String binlogStart;
    private String binlogEnd;
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

    public byte[] getStatement() {
        return statement;
    }

    public void setStatement(byte[] statement) {
        this.statement = statement;
    }

    public String getStatementHash() {
        return statementHash;
    }

    public void setStatementHash(String statementHash) {
        this.statementHash = statementHash;
    }

    public String getStatementType() {
        return statementType;
    }

    public void setStatementType(String statementType) {
        this.statementType = statementType;
    }

    public String getDialectSpecific() {
        return dialectSpecific;
    }

    public void setDialectSpecific(String dialectSpecific) {
        this.dialectSpecific = dialectSpecific;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

    public String getExecFingerprint() {
        return execFingerprint;
    }

    public void setExecFingerprint(String execFingerprint) {
        this.execFingerprint = execFingerprint;
    }

    public Long getPreimageRows() {
        return preimageRows;
    }

    public void setPreimageRows(Long preimageRows) {
        this.preimageRows = preimageRows;
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

    public String getBinlogStart() {
        return binlogStart;
    }

    public void setBinlogStart(String binlogStart) {
        this.binlogStart = binlogStart;
    }

    public String getBinlogEnd() {
        return binlogEnd;
    }

    public void setBinlogEnd(String binlogEnd) {
        this.binlogEnd = binlogEnd;
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
