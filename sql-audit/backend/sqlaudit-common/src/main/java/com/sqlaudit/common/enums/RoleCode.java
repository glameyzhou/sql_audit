package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 内置角色 code（设计文档 §8.2 权限矩阵；V1 种子 {@code t_role}）。
 *
 * <p>用途：<strong>代码里引用角色</strong>（{@code @RequireRole} 鉴权、审批链节点、种子账号授权）
 * 一律用本枚举，不再手写 {@code "ADMIN"} 这类字面量——角色判断写错一个字母就是越权或误拒。</p>
 *
 * <p>边界：{@code t_role.code} 是数据列，角色实体（{@code Role.code}）、JWT 里的 roles、
 * 审计里的 actor_role 仍按字符串存取（它们来自库或 token，不是手写代码）；需要与枚举比对时
 * 用 {@link #getCode()}。系统动作的审计角色 {@code "SYSTEM"} 不是可授予的角色，不在本枚举内。</p>
 */
public enum RoleCode implements CodedEnum {
    /** 开发：提交工单 / 查询通道 */
    DEV,
    /** 主管：审批（非本人） */
    LEAD,
    /** DBA：执行 / 二次确认、规则与流程配置 */
    DBA,
    /** 管理员：凭据管理、审计检索导出、全量配置 */
    ADMIN;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static RoleCode fromCode(String code) {
        return CodedEnum.fromCode(RoleCode.class, code);
    }
}
