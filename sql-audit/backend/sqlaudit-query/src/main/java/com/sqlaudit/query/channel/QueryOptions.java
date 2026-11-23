package com.sqlaudit.query.channel;

/**
 * 查询通道保护参数（README §4.1 保护表：行数 / 超时 / 结果大小）。
 *
 * <p>不可变；默认 maxLimit=1000、queryTimeoutSeconds=10。</p>
 */
public final class QueryOptions {

    /** 默认行数上限（README §4.1：LIMIT 默认 1000，可配） */
    public static final int DEFAULT_MAX_LIMIT = 1000;
    /** 默认查询超时秒数（README §4.1：10s） */
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;

    private final int maxLimit;
    private final int queryTimeoutSeconds;
    /** 单行字节上限（README §4.1 结果大小；字节上限校验 TODO，W2 掩码/导出阶段实现） */
    private final int maxRowBytes;
    /** 结果总字节上限（同上 TODO） */
    private final long maxTotalBytes;

    public QueryOptions() {
        this(DEFAULT_MAX_LIMIT, DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    public QueryOptions(int maxLimit, int queryTimeoutSeconds) {
        this(maxLimit, queryTimeoutSeconds, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public QueryOptions(int maxLimit, int queryTimeoutSeconds, int maxRowBytes, long maxTotalBytes) {
        if (maxLimit <= 0) {
            throw new IllegalArgumentException("maxLimit 必须 > 0: " + maxLimit);
        }
        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds 必须 > 0: " + queryTimeoutSeconds);
        }
        this.maxLimit = maxLimit;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxRowBytes = maxRowBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public int maxLimit() {
        return maxLimit;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public int maxRowBytes() {
        return maxRowBytes;
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }
}
