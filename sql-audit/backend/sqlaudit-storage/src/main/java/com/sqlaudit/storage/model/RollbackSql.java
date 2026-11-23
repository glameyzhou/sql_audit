package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RollbackStatus;
import java.time.LocalDateTime;

/**
 * 回滚主记录实体（对应 rollback_sql 表，README §4.4）。
 * <p>唯一写入方：sqlaudit-rollback。</p>
 */
public class RollbackSql {

    private Long id;
    private Long unitId;
    private Integer stmtCount;
    private Long totalBytes;
    /** MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT */
    private RollbackGenerator generator;
    /** generated/verified/applied/expired/not_available，见 RollbackStatus */
    private RollbackStatus status;
    /** FULL/PARTIAL/NONE，见 RollbackCoverage */
    private RollbackCoverage coverage;
    private String unavailableReason;
    /** 前镜像指纹，用于依赖检测 */
    private String preimageHash;
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

    public RollbackGenerator getGenerator() {
        return generator;
    }

    public void setGenerator(RollbackGenerator generator) {
        this.generator = generator;
    }

    public RollbackStatus getStatus() {
        return status;
    }

    public void setStatus(RollbackStatus status) {
        this.status = status;
    }

    public RollbackCoverage getCoverage() {
        return coverage;
    }

    public void setCoverage(RollbackCoverage coverage) {
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
