package com.sqlaudit.common.exception;

/**
 * 平台统一异常基类。
 * <p>所有业务异常带稳定的错误码，便于前端与告警路由。</p>
 */
public class SqlauditException extends RuntimeException {

    private final String code;

    public SqlauditException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SqlauditException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
