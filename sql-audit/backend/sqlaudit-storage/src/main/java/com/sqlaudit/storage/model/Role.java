package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.InstanceScope;
import java.time.LocalDateTime;

/**
 * 角色实体（对应 t_role 表）。
 *
 * <p>角色完全数据化：{@code code} 是纯数据列，<strong>代码里不做角色码字面量判断</strong>。
 * 「超级管理员」不是特例，只是一个持有全部权限点的普通角色。</p>
 *
 * <p>两个标志位语义严格区分：</p>
 * <ul>
 *   <li>{@code builtin}：只控制「不可删除 / code 不可改」，<strong>不参与任何鉴权判定</strong>；</li>
 *   <li>{@code instanceScope}：数据范围轴（见 {@link InstanceScope}），
 *       {@code SELECTED} 时实例集合取 {@code t_role_instance}。</li>
 * </ul>
 */
public class Role {

    private Long id;
    /** 角色码，唯一；纯数据，代码不引用字面量 */
    private String code;
    private String name;
    private String description;
    /** 内置角色：不可删除、code 不可改（不参与鉴权判定） */
    private boolean builtin;
    /** 数据范围：ALL 全部实例 / SELECTED 仅 t_role_instance 授权的实例 */
    private InstanceScope instanceScope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public InstanceScope getInstanceScope() {
        return instanceScope;
    }

    public void setInstanceScope(InstanceScope instanceScope) {
        this.instanceScope = instanceScope;
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
