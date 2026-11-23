package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.UserSource;
import com.sqlaudit.common.enums.UserStatus;
import java.time.LocalDateTime;

/**
 * 用户实体（对应 t_user 表，设计文档 §8 账号与权限）。
 *
 * <p>唯一写入方：sqlaudit-auth。三种账号来源：企微（WEWORK，走 OAuth 免密）与
 * 本地应急账号（LOCAL，bcrypt 密码 + 可选 TOTP）。特权操作每次实时校验 status。</p>
 */
public class User {

    private Long id;
    /** 登录名（企微账号 / 本地应急账号） */
    private String username;
    /** 显示名 */
    private String displayName;
    private String email;
    /** WEWORK / LOCAL */
    private UserSource source;
    /** 企微 userid/openid；source=WEWORK 时非空 */
    private String weworkOpenid;
    /** 本地账号 bcrypt 哈希；source=LOCAL 时非空 */
    private String passwordHash;
    /** 本地账号 TOTP 密钥（加密） */
    private String totpSecret;
    /** ACTIVE / DISABLED */
    private UserStatus status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserSource getSource() {
        return source;
    }

    public void setSource(UserSource source) {
        this.source = source;
    }

    public String getWeworkOpenid() {
        return weworkOpenid;
    }

    public void setWeworkOpenid(String weworkOpenid) {
        this.weworkOpenid = weworkOpenid;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
