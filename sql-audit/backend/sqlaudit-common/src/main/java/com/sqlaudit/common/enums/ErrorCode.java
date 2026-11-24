package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 平台统一错误码（接口响应体 {@code {"code": ..., "message": ...}} 里的 {@code code}）。
 *
 * <p>错误码是前后端契约：前端按 {@code TICKET_NOT_FOUND} 这类取值分流（见
 * frontend {@code tickets/Detail.vue}）。抛异常一律传本枚举
 * （{@code new SqlauditException(ErrorCode.TICKET_NOT_FOUND, "...")}），不再手写字符串——
 * 错误码拼错一个字母，前端的分支就静默失效。</p>
 *
 * <p>取值与改造前的字面量逐个对应，对外响应不变。新增错误码在对应分组的末尾追加。</p>
 */
public enum ErrorCode implements CodedEnum {
    // ---- 通用 ----
    /** 未归类 / 兜底 */
    BUSINESS_ERROR,
    /** 未登录或 token 无效（401） */
    UNAUTHORIZED,
    /** 已登录但角色不足（403） */
    FORBIDDEN,
    /** 请求参数非法（含请求体里的枚举取值不在合法范围内、JSON 无法解析） */
    INVALID_REQUEST,
    /** 状态机非法迁移（409） */
    ILLEGAL_STATE_TRANSITION,
    /** 未预期异常（500） */
    INTERNAL_ERROR,
    /** 凭据加解密失败 */
    CREDENTIAL_CRYPTO,

    // ---- 实例与库表元数据 ----
    INSTANCE_NOT_FOUND,
    IOTDB_NOT_SUPPORTED,
    /** 实例缺少 RO_META 只读元数据凭据 */
    RO_META_CREDENTIAL_MISSING,
    /** RO_META 凭据解密失败 */
    RO_META_CREDENTIAL_INVALID,
    /** 库 / 表清单查询失败 */
    META_QUERY_FAILED,

    // ---- 用户 / 角色 / 权限（RBAC）----
    USERNAME_EXISTS,
    USER_NOT_FOUND,
    ROLE_NOT_FOUND,
    /** 内置角色不可删除、code 不可改（400） */
    BUILTIN_ROLE_IMMUTABLE,
    /**
     * 防自锁不变式被破坏（409）：任一 RBAC 写操作提交后，仍须至少有一个 ACTIVE 用户
     * 经任意角色持有某个元权限点（{@code t_permission.meta}）。
     */
    RBAC_LOCKOUT,

    // ---- 工单提交与执行前门禁 ----
    /** 工单没有任何语句 */
    EMPTY_UNITS,
    /** 工单未指定目标实例 */
    EMPTY_INSTANCES,
    /** 存在 ERROR 级规则命中，禁止提交 */
    ERROR_RULE_BLOCKED,
    /** 影响面评估未完成（评估已并入提交链路同步完成；保留供语义完整性 / 兼容） */
    IMPACT_ASSESSMENT_INCOMPLETE,
    /** 审批前采集表结构指纹失败 */
    FINGERPRINT_CAPTURE_FAILED,
    /** 条件更新影响行数 0：状态已被并发修改 */
    TICKET_STATE_CONFLICT,
    /** 目标实例不在当前用户的数据范围内（403） */
    INSTANCE_OUT_OF_SCOPE,
    /** SQL 文本超长（单工单 1 MiB / 单条查询 64 KiB，400） */
    SQL_TOO_LARGE,
    /** 语句引用了实例默认库之外的对象（MySQL 限定名跨库，400） */
    SCHEMA_OUT_OF_BOUND,

    // ---- 审批 ----
    TICKET_NOT_FOUND,
    TICKET_NOT_AUDITING,
    /** 无权查看该工单（非提交人 / 非审批人 / 无「查看全部工单」能力，403） */
    TICKET_ACCESS_DENIED,
    /** 该风险等级的生效审批链为空（非 LOW 不得免审，400） */
    APPROVAL_FLOW_NOT_CONFIGURED,
    /** 审批链节点解析不出「提交人以外的有效审批人」（400） */
    NO_ELIGIBLE_APPROVER,
    /** 同一范围（全局 / 某实例）+ 风险等级已存在审批流（409） */
    DUPLICATE_FLOW,
    /** 审批流不存在 */
    FLOW_NOT_FOUND,
    /** 审批流节点配置非法（组内 mode 不一致 / node_order 不连续 / 引用对象无效，400） */
    FLOW_NODE_INVALID,
    /** 工单已生成审批节点，不得重复生成 */
    APPROVAL_NODES_EXIST,
    /** 提交人不得审批自己的工单 */
    SELF_APPROVAL_FORBIDDEN,
    /** 提交人不得二次确认自己的工单 */
    SELF_CONFIRM_FORBIDDEN,
    APPROVAL_ALREADY_DECIDED,
    APPROVAL_TARGET_NOT_FOUND,
    NO_PENDING_APPROVAL,

    // ---- 回滚回放 ----
    ROLLBACK_NOT_FOUND,
    ROLLBACK_INVALID_STATUS,
    /** 回滚当前状态不允许执行（仅 generated / verified 可执行） */
    ROLLBACK_NOT_EXECUTABLE,
    ROLLBACK_EXPIRED,
    ROLLBACK_NO_UNIT,
    ROLLBACK_NO_INSTANCE,
    ROLLBACK_PARAM_PARSE_FAILED;

    /** 对外错误码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 错误码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static ErrorCode fromCode(String code) {
        return CodedEnum.fromCode(ErrorCode.class, code);
    }
}
