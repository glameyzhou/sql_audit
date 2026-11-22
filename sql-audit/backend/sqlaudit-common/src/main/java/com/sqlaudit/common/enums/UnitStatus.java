package com.sqlaudit.common.enums;

import java.util.Set;

/**
 * 执行单元状态机（设计文档 §3.2 / §3.3）。
 *
 * <pre>
 * pending → leased → executing → (success | failed | skipped)
 * </pre>
 *
 * <p>队列幂等协议（§3.3）补充：</p>
 * <ul>
 *   <li>异常超时回收：{@code leased} 且 {@code intent_written=0} 时回 {@code pending}；</li>
 *   <li>{@code executing} 心跳停滞且未确认 → 转 {@code needs_confirm}（禁止自动重试）；</li>
 *   <li>{@code needs_confirm} 由人工核对后标记 {@code success} / {@code failed}；</li>
 *   <li>「一键续跑」允许 {@code failed} 重新入队回 {@code pending}（只跑 failed/pending）。</li>
 * </ul>
 *
 * <p>不变式（实现时写成断言，见 §3.3）：</p>
 * <ul>
 *   <li>{@code intent_written=1} 只能出现在 {@code executing/success/failed/needs_confirm}；</li>
 *   <li>{@code status=executing} 必然 {@code intent_written=1}。</li>
 * </ul>
 */
public enum UnitStatus {
    PENDING,
    LEASED,
    EXECUTING,
    SUCCESS,
    FAILED,
    SKIPPED,
    NEEDS_CONFIRM;

    /** 是否允许从当前状态迁移到 {@code target}。 */
    public boolean canTransitionTo(UnitStatus target) {
        return switch (this) {
            case PENDING -> Set.of(LEASED).contains(target);
            case LEASED -> Set.of(PENDING, EXECUTING).contains(target);
            case EXECUTING -> Set.of(SUCCESS, FAILED, NEEDS_CONFIRM).contains(target);
            case FAILED -> Set.of(PENDING).contains(target); // 一键续跑
            case NEEDS_CONFIRM -> Set.of(SUCCESS, FAILED).contains(target); // 人工核对
            case SUCCESS, SKIPPED -> false; // 终态
        };
    }

    /** 是否为终态（不再迁移）。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == SKIPPED;
    }
}
