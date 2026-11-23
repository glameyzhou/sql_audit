package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.UserSource;
import com.sqlaudit.common.enums.UserStatus;
import java.time.LocalDateTime;

/**
 * 用户实体（对应 t_user 表）。
 *
 * <p>唯一写入方：sqlaudit-server。账号来源见 {@link UserSource}（当前只用 LOCAL，
 * bcrypt 密码；企微 OAuth 未实现）。<strong>没有删除用户的功能，停用即可</strong>
 * （无 deleted_at 列），{@code status} 每请求实时校验，停用即刻失效。</p>
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
    /** bcrypt 哈希；source=LOCAL 时非空 */
    private String passwordHash;
    /** ACTIVE / DISABLED */
    private UserStatus status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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
}
