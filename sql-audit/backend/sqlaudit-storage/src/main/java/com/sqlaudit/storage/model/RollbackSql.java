package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 回滚主记录实体（对应 rollback_sql 表，设计文档 §5.9）。
 * <p>唯一写入方：sqlaudit-rollback。</p>
 */
public class RollbackSql {

    private Long id;
    private Long unitId;
    private Integer stmtCount;
    private Long totalBytes;
    /** MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT */
    private String generator;
    /** generated/verified/applied/expired/not_available，见 RollbackStatus */
    private String status;
    /** FULL/PARTIAL/NONE，见 RollbackCoverage */
    private String coverage;
    private String unavailableReason;
    /** 前镜像指纹，用于依赖检测 */
    private String preimageHash;
    private String binlogStart;
    private String binlogEnd;
    /** 影子表/IoTDB 快照位置 */
    private String snapshotUri;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Integer getStmtCount() {
        return stmtCount;
    }

    public void setStmtCount(Integer stmtCount) {
        this.stmtCount = stmtCount;
    }

    public Long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(Long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public String getGenerator() {
        return generator;
    }

    public void setGenerator(String generator) {
        this.generator = generator;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCoverage() {
        return coverage;
    }

    public void setCoverage(String coverage) {
        this.coverage = coverage;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(String unavailableReason) {
        this.unavailableReason = unavailableReason;
    }

    public String getPreimageHash() {
        return preimageHash;
    }

    public void setPreimageHash(String preimageHash) {
        this.preimageHash = preimageHash;
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

    public String getSnapshotUri() {
        return snapshotUri;
    }

    public void setSnapshotUri(String snapshotUri) {
        this.snapshotUri = snapshotUri;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
