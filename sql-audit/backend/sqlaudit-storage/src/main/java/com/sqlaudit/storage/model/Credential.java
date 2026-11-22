package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 生产库加密凭据（对应 t_credential 表，设计文档 §5 生产实例与凭据）。
 *
 * <p>唯一写入方：sqlaudit-auth；查看凭据须写审计，绝不回显明文。{@code passwordEnc}
 * 存 {@code CredentialCrypto} 加密后的 {@code <ivBase64>.<ciphertextBase64>} 文本。</p>
 */
public class Credential {

    private Long id;
    /** 所属实例 id（t_instance.id） */
    private Long instanceId;
    /** RO_META / RO_DATA / RW_EXEC 三类生产账号物理隔离 */
    private String accountType;
    private String username;
    /** AES-GCM 密文（base64），绝不明文落库 */
    private String passwordEnc;
    /** 主密钥版本，轮换用 */
    private Integer keyVersion;
    private LocalDateTime lastRotatedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordEnc() {
        return passwordEnc;
    }

    public void setPasswordEnc(String passwordEnc) {
        this.passwordEnc = passwordEnc;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }

    public LocalDateTime getLastRotatedAt() {
        return lastRotatedAt;
    }

    public void setLastRotatedAt(LocalDateTime lastRotatedAt) {
        this.lastRotatedAt = lastRotatedAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
