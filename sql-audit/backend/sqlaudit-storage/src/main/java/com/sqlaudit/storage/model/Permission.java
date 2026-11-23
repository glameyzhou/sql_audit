package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 权限点实体（对应 t_permission 表，见 sql-audit-rbac-权限可配置化设计.md）。
 *
 * <p>目录由 {@code PermissionCode} 枚举在启动时 insert-if-absent 同步；{@code customized}
 * 标记「界面是否已配置过」——false 走代码兜底默认角色，true 走 t_role_permission 覆盖
 * （可为空 = 该权限点对所有人关闭）。</p>
 */
public class Permission {

    private Long id;
    /** 权限点 code（= PermissionCode 枚举名） */
    private String code;
    /** 中文显示名 */
    private String name;
    private String description;
    /** 界面是否已配置过（false = 用代码兜底默认角色） */
    private boolean customized;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isCustomized() {
        return customized;
    }

    public void setCustomized(boolean customized) {
        this.customized = customized;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
