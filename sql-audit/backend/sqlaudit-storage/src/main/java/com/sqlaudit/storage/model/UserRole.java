package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 用户-角色关联（对应 t_user_role 表，多对多）。
 *
 * <p>唯一写入方：sqlaudit-auth。授权人（granted_by）留痕，配合审计链追溯「谁给了谁什么角色」。</p>
 */
public class UserRole {

    private Long userId;
    private Long roleId;
    /** 授权人（user_id） */
    private Long grantedBy;
    private LocalDateTime createdAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
