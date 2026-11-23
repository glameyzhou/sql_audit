package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 权限点实体（对应 t_permission 表）——界面上「勾选一项功能」的单位。
 *
 * <p>目录由 Flyway 的 {@code V2__seed.sql} 维护，界面只读：权限点要新增 / 改名 / 改绑定接口
 * 都走迁移文件，避免「代码里一份、库里一份」的漂移。{@code code} 只供迁移文件引用，
 * <strong>代码里不做权限码字面量判断</strong>（判定一律走「HTTP 方法 + 处理器模式」）。</p>
 *
 * <ul>
 *   <li>{@code allUsers}：所有登录用户默认拥有，不写 {@code t_role_permission} 授权行；</li>
 *   <li>{@code meta}：元权限——防自锁不变式的对象（任一 RBAC 写操作后，仍须至少有一个
 *       ACTIVE 用户经任意角色持有某个元权限点，否则整笔回滚）。</li>
 * </ul>
 */
public class Permission {

    private Long id;
    /** 权限点 code，唯一；供迁移文件引用，代码不引用 */
    private String code;
    /** 界面显示名 */
    private String name;
    /** 界面分组名（中文） */
    private String module;
    private String description;
    /** 界面排序 */
    private int sort;
    /** 所有登录用户默认拥有 */
    private boolean allUsers;
    /** 元权限：防自锁不变式的对象 */
    private boolean meta;
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

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSort() {
        return sort;
    }

    public void setSort(int sort) {
        this.sort = sort;
    }

    public boolean isAllUsers() {
        return allUsers;
    }

    public void setAllUsers(boolean allUsers) {
        this.allUsers = allUsers;
    }

    public boolean isMeta() {
        return meta;
    }

    public void setMeta(boolean meta) {
        this.meta = meta;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
