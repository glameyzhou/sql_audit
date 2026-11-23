package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 审计日志实体（对应 audit_log 表，README §4.1）。
 *
 * <p>append-only + hash 链。关键约定：</p>
 * <ul>
 *   <li>{@code id} 是物理主键，不参与 hash 链；{@code seq} 由 chain_head 行锁内分配；</li>
 *   <li>{@code hash = SHA256(prev_hash || seq || canonical_json(record))}，算明文；</li>
 *   <li>{@code detail} 是 AES-GCM 密文，明文参与哈希，密钥轮换不破链。</li>
 * </ul>
 * <p>唯一写入方：sqlaudit-audit（单写入点）。</p>
 */
public class AuditLog {

    private Long id;
    private Long seq;
    private Long actorId;
    private String actorName;
    private String actorRole;
    private String action;
    private String targetType;
    private String targetId;
    private Long instanceId;
    /** 加密后的 detail（VARBINARY） */
    private byte[] detail;
    private String result;
    private String clientIp;
    private String userAgent;
    private String traceId;
    private String prevHash;
    private String hash;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public byte[] getDetail() {
        return detail;
    }

    public void setDetail(byte[] detail) {
        this.detail = detail;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
