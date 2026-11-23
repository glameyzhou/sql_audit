package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

/**
 * 工单状态机（设计文档 §3.2）。
 *
 * <pre>
 * draft → submitted → auditing → (approved | rejected) → scheduled → executing
 *   → (succeeded | partially_succeeded | failed | needs_confirm) → closed
 * 任意态可 cancelled；approved 超时未执行 expired。
 * </pre>
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>{@code approved} 表示「批准但尚未执行」，执行由显式动作或低峰窗口触发；</li>
 *   <li>{@code needs_confirm}：SQL 可能已在生产库生效但平台库状态未确认，禁止自动重试；</li>
 *   <li>{@code partially_succeeded}：多语句工单不是事务，部分成功需按 unit 倒序回滚。</li>
 * </ul>
 *
 * <p>非法迁移必须由调用方（sqlaudit-workflow）抛异常，不得静默忽略（§B-3 验收）。</p>
 *
 * <p>存储码为小写（{@code t_ticket.status}，§3.2），见 {@link CodedEnum}。</p>
 */
public enum TicketStatus implements CodedEnum {
    DRAFT("draft"),
    SUBMITTED("submitted"),
    AUDITING("auditing"),
    APPROVED("approved"),
    REJECTED("rejected"),
    SCHEDULED("scheduled"),
    EXECUTING("executing"),
    SUCCEEDED("succeeded"),
    PARTIALLY_SUCCEEDED("partially_succeeded"),
    FAILED("failed"),
    NEEDS_CONFIRM("needs_confirm"),
    CLOSED("closed"),
    CANCELLED("cancelled"),
    EXPIRED("expired");

    private final String code;

    TicketStatus(String code) {
        this.code = code;
    }

    /** DB / JSON 存储码（小写）。 */
    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static TicketStatus fromCode(String code) {
        return CodedEnum.fromCode(TicketStatus.class, code);
    }

    /** 是否允许从当前状态迁移到 {@code target}。 */
    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case DRAFT -> Set.of(SUBMITTED, CANCELLED).contains(target);
            case SUBMITTED -> Set.of(AUDITING, CANCELLED).contains(target);
            case AUDITING -> Set.of(APPROVED, REJECTED, CANCELLED).contains(target);
            case APPROVED -> Set.of(SCHEDULED, EXECUTING, EXPIRED, CANCELLED).contains(target);
            case REJECTED -> Set.of(CLOSED, CANCELLED).contains(target);
            case SCHEDULED -> Set.of(EXECUTING, CANCELLED).contains(target);
            case EXECUTING -> Set.of(SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, NEEDS_CONFIRM).contains(target);
            case SUCCEEDED -> Set.of(CLOSED).contains(target);
            case PARTIALLY_SUCCEEDED -> Set.of(CLOSED).contains(target);
            case FAILED -> Set.of(CLOSED).contains(target);
            case NEEDS_CONFIRM -> Set.of(SUCCEEDED, FAILED, CLOSED).contains(target);
            // 终态：CLOSED / CANCELLED / EXPIRED 不再迁移（expired 需重新审批，走新工单）
            case CLOSED, CANCELLED, EXPIRED -> false;
        };
    }

    /** 是否为终态（不再迁移）。 */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == EXPIRED;
    }
}
