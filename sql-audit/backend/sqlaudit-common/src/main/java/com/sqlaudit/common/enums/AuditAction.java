package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审计动作（README §4.1 事件表，{@code t_audit_log.action}）。
 *
 * <p>审计日志是 append-only 的 hash 链：历史行里有什么值就得原样读出来重算哈希，所以
 * {@code AuditEvent} / {@code AuditLog} 的字段保持 String；本枚举只约束<strong>代码里手写的
 * 取值</strong>——写审计时传枚举（{@code AuditEvent.builder().action(AuditAction.X)}），
 * 落库仍是 {@link #getCode()}，与之前逐字节相同。</p>
 *
 * <p>取值统一为大写下划线命名（{@code QUERY_EXECUTED} / {@code TICKET_STATE_CHANGE}），
 * 与 {@code ROLLBACK_EXECUTE} 等其余动作同风格，方便按动作检索与告警路由。</p>
 */
public enum AuditAction implements CodedEnum {
    /** 登录（README §4.1 事件表） */
    LOGIN("LOGIN"),
    /** 创建本地账号 */
    USER_CREATE("USER_CREATE"),
    /** 修改用户资料 / 启停用 */
    USER_UPDATE("USER_UPDATE"),
    /** 重置密码 */
    PASSWORD_RESET("PASSWORD_RESET"),
    /** 分配角色 */
    ROLE_ASSIGN("ROLE_ASSIGN"),
    /** 配置角色-权限点映射 */
    PERMISSION_ASSIGN("PERMISSION_ASSIGN"),
    /** 新增角色（RBAC 角色管理） */
    ROLE_CREATE("ROLE_CREATE"),
    /** 修改角色（权限点 / 数据范围等） */
    ROLE_UPDATE("ROLE_UPDATE"),
    /** 删除角色 */
    ROLE_DELETE("ROLE_DELETE"),
    /** 新增实例 */
    INSTANCE_CREATE("INSTANCE_CREATE"),
    /** 修改实例 */
    INSTANCE_UPDATE("INSTANCE_UPDATE"),
    /** 删除实例 */
    INSTANCE_DELETE("INSTANCE_DELETE"),
    /** 新增审核规则 */
    RULE_CREATE("RULE_CREATE"),
    /** 修改审核规则 */
    RULE_UPDATE("RULE_UPDATE"),
    /** 删除审核规则 */
    RULE_DELETE("RULE_DELETE"),
    /** 新增审批流规则 */
    FLOW_CREATE("FLOW_CREATE"),
    /** 修改审批流规则 */
    FLOW_UPDATE("FLOW_UPDATE"),
    /** 删除审批流规则 */
    FLOW_DELETE("FLOW_DELETE"),
    /** 查询通道执行（成功与被拒都记，见 AuditResult） */
    QUERY_EXECUTED("QUERY_EXECUTED"),
    /** 工单状态迁移 */
    TICKET_STATE_CHANGE("TICKET_STATE_CHANGE"),
    /** 执行单元执行（README §4.1 事件表） */
    UNIT_EXEC("UNIT_EXEC"),
    /** 回滚回放（整次） */
    ROLLBACK_EXECUTE("ROLLBACK_EXECUTE"),
    /** 回滚回放（逐条反向语句） */
    ROLLBACK_STATEMENT_APPLIED("ROLLBACK_STATEMENT_APPLIED"),
    /** 设置 / 清空表备注（PUT /api/table-remarks；detail 里区分 set / clear） */
    TABLE_REMARK_UPDATE("TABLE_REMARK_UPDATE");

    private final String code;

    AuditAction(String code) {
        this.code = code;
    }

    /** 审计日志里的存储值。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 存储值 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static AuditAction fromCode(String code) {
        return CodedEnum.fromCode(AuditAction.class, code);
    }
}
