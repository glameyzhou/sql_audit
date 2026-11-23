package com.sqlaudit.common.exception;

import com.sqlaudit.common.enums.ErrorCode;

import java.util.Objects;

/**
 * 平台统一异常基类。
 * <p>所有业务异常带稳定的错误码，便于前端与告警路由。错误码一律取自 {@link ErrorCode} 枚举
 * （构造函数只收枚举，手写字符串在编译期就被挡住）；{@link #getCode()} 返回的是对外响应体里的
 * 错误码字符串。</p>
 */
public class SqlauditException extends RuntimeException {

    private final ErrorCode errorCode;

    public SqlauditException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public SqlauditException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 对外错误码（响应体 {@code code} 字段的取值）。 */
    public String getCode() {
        return errorCode.getCode();
    }

    /** 错误码枚举（代码里按错误码分支用这个，不要拿字符串比较）。 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
