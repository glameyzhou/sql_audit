package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

/**
 * 权限点目录（RBAC 可配置化，见 sql-audit-rbac-权限可配置化设计.md）。
 *
 * <p>把「角色 → 权限点」从硬编码的 {@code @RequireRole} 解耦为可配置：每个权限点带
 * {@link #getDefaultRoles()}（= 代码兜底，对齐设计文档 §8.2 权限矩阵），界面在
 * {@code t_role_permission} 里配置后覆盖兜底（见 {@code PermissionService}）。</p>
 *
 * <p>约定与 {@link RoleCode} 一致：{@link #getCode()} 返回枚举名（= 存储码），
 * {@link #fromCode} 大小写不敏感、未知取值抛 {@link IllegalArgumentException}。</p>
 */
public enum PermissionCode implements CodedEnum {

    /** 提交工单（全体可提交） */
    TICKET_SUBMIT("提交工单", "提交变更工单", true, RoleCode.DEV, RoleCode.LEAD, RoleCode.DBA, RoleCode.ADMIN),
    /** 查询通道（全体只读查询） */
    QUERY("查询通道", "线上只读查询", true, RoleCode.DEV, RoleCode.LEAD, RoleCode.DBA, RoleCode.ADMIN),
    /** 审批（非本人） */
    APPROVE("审批", "审批工单（非本人）", true, RoleCode.LEAD, RoleCode.DBA, RoleCode.ADMIN),
    /** 执行 / 二次确认 / 回滚执行 */
    EXECUTE("执行/二次确认", "执行工单、二次确认、回滚执行", true, RoleCode.DBA, RoleCode.ADMIN),
    /** 规则 / 流程配置 */
    CONFIG_RULE_FLOW("规则/流程配置", "审核规则与审批流配置", true, RoleCode.DBA, RoleCode.ADMIN),
    /** 实例接入与凭据 */
    INSTANCE_ADMIN("实例接入与凭据", "实例与数据库凭据管理", true, RoleCode.ADMIN),
    /** 审计检索 / 导出 */
    AUDIT_EXPORT("审计检索/导出", "审计日志检索与导出", true, RoleCode.DBA, RoleCode.ADMIN),
    /** 用户管理 */
    USER_ADMIN("用户管理", "本地账号、角色分配、密码重置", true, RoleCode.ADMIN),
    /** 权限配置（元权限：硬编码 ADMIN，不进入可配置矩阵，防自锁） */
    PERMISSION_ADMIN("权限配置", "角色-权限点映射配置（元权限，不可配置）", false, RoleCode.ADMIN);

    private final String displayName;
    private final String description;
    /** false 表示永不进 DB 配置，直接按 defaultRoles 判定（仅 PERMISSION_ADMIN）。 */
    private final boolean configurable;
    private final List<RoleCode> defaultRoles;

    PermissionCode(String displayName, String description, boolean configurable, RoleCode... defaultRoles) {
        this.displayName = displayName;
        this.description = description;
        this.configurable = configurable;
        this.defaultRoles = List.of(defaultRoles);
    }

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static PermissionCode fromCode(String code) {
        return CodedEnum.fromCode(PermissionCode.class, code);
    }

    /** 中文显示名。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 权限点说明。 */
    public String getDescription() {
        return description;
    }

    /** 是否允许在界面配置（false = 硬编码兜底，永不进 DB）。 */
    public boolean isConfigurable() {
        return configurable;
    }

    /** 兜底默认角色（界面未配置时生效；对齐 §8.2 权限矩阵）。 */
    public List<RoleCode> getDefaultRoles() {
        return defaultRoles;
    }
}
