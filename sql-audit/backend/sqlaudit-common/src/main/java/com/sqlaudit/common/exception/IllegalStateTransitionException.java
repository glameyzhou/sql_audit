package com.sqlaudit.common.exception;

/**
 * 状态机非法迁移异常（设计文档 §B-3 验收：「状态机的非法迁移必须抛异常而不是静默忽略」）。
 */
public class IllegalStateTransitionException extends SqlauditException {

    public IllegalStateTransitionException(String from, String to) {
        super("ILLEGAL_STATE_TRANSITION", "非法状态迁移：" + from + " → " + to);
    }

    public IllegalStateTransitionException(String from, String to, String reason) {
        super("ILLEGAL_STATE_TRANSITION", "非法状态迁移：" + from + " → " + to + "（" + reason + "）");
    }
}
