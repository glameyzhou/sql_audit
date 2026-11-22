package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 角色实体（对应 t_role 表，设计文档 §8.2 权限矩阵）。
 *
 * <p>内置角色（V1 种子）：DEV（开发，提交工单/查询通道）、LEAD（主管，审批非本人）、
 * DBA（执行/二次确认、规则与流程配置）、ADMIN（管理员，凭据管理/审计检索导出/全量配置）。</p>
 */
public class Role {

    private Long id;
    /** DEV / LEAD / DBA / ADMIN */
    private String code;
    private String name;
    private String description;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
