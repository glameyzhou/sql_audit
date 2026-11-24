package com.sqlaudit.query.channel;

import java.util.Collections;
import java.util.List;

/**
 * 查询通道执行结果（README §4.1）。
 *
 * <p><strong>query_log 落库所需字段已带齐</strong>（W2 集成时直接取用，本模块不落库）：
 * {@code instanceId} / {@code sql}（原文，落库前由调用方加密）/
 * {@code rowCount} / {@code elapsedMs} / {@code truncated}；
 * {@code sqlHash} 由调用方对 {@link #sql} 计算，{@code userId} / {@code traceId} /
 * {@code clientIp} 来自请求上下文。结果集本身不落库（README §4.1：query_log 只存 SQL 全文、行数、耗时、截断标记）。</p>
 *
 * <p>结果页「平台已注入 LIMIT 1000」文案由 {@link #notice} 支撑
 * （来自 LIMIT 注入层的 injected / tightened / originalLimit / effectiveLimit）。</p>
 */
public final class QueryResult {

    /** 执行是否成功（门禁拒绝 / 执行异常均为 false） */
    private final boolean success;
    /** 失败原因（成功时为 null） */
    private final String reason;

    private final List<String> columns;
    private final List<List<Object>> rows;
    /** 返回行数（不含被截断部分） */
    private final int rowCount;
    /** 耗时毫秒 */
    private final long elapsedMs;
    /** 结果集是否超过上限被截断 */
    private final boolean truncated;

    /** 实例 ID（query_log 落库字段） */
    private final long instanceId;
    /** 用户原始 SQL（query_log 落库字段，落库前由调用方加密） */
    private final String sql;
    /** 实际执行的 SQL（注入/收紧后；未改写时与原文相同），前端展示用 */
    private final String rewrittenSql;
    /** 用户原 LIMIT；null 表示原 SQL 无 LIMIT */
    private final Integer originalLimit;
    /** 平台是否注入 LIMIT */
    private final boolean injected;
    /** 平台是否收紧了过大的用户 LIMIT */
    private final boolean tightened;
    /** 结果页提示文案（如「平台已注入 LIMIT 1000」），未改写时为 null */
    private final String notice;

    private QueryResult(boolean success, String reason,
                        List<String> columns, List<List<Object>> rows,
                        int rowCount, long elapsedMs, boolean truncated,
                        long instanceId, String sql, String rewrittenSql,
                        Integer originalLimit, boolean injected, boolean tightened, String notice) {
        this.success = success;
        this.reason = reason;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        this.rowCount = rowCount;
        this.elapsedMs = elapsedMs;
        this.truncated = truncated;
        this.instanceId = instanceId;
        this.sql = sql;
        this.rewrittenSql = rewrittenSql;
        this.originalLimit = originalLimit;
        this.injected = injected;
        this.tightened = tightened;
        this.notice = notice;
    }

    /** 门禁拒绝（elapsedMs 为门禁耗时）。web 层对「控制台实用语句不可翻译」等场景同用此出口。 */
    public static QueryResult rejected(long instanceId, String sql, String reason, long elapsedMs) {
        return new QueryResult(false, reason, null, null, 0, elapsedMs, false,
                instanceId, sql, sql, null, false, false, null);
    }

    /** 执行失败（JDBC 异常等）。 */
    static QueryResult failed(long instanceId, String sql, String reason, long elapsedMs) {
        return new QueryResult(false, reason, null, null, 0, elapsedMs, false,
                instanceId, sql, sql, null, false, false, null);
    }

    /** 执行成功。 */
    static QueryResult success(long instanceId, String sql, String rewrittenSql,
                               Integer originalLimit, boolean injected, boolean tightened, String notice,
                               List<String> columns, List<List<Object>> rows,
                               long elapsedMs, boolean truncated) {
        return new QueryResult(true, null, columns, rows, rows.size(), elapsedMs, truncated,
                instanceId, sql, rewrittenSql, originalLimit, injected, tightened, notice);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public int getRowCount() {
        return rowCount;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public long getInstanceId() {
        return instanceId;
    }

    public String getSql() {
        return sql;
    }

    public String getRewrittenSql() {
        return rewrittenSql;
    }

    public Integer getOriginalLimit() {
        return originalLimit;
    }

    public boolean isInjected() {
        return injected;
    }

    public boolean isTightened() {
        return tightened;
    }

    public String getNotice() {
        return notice;
    }

    /**
     * 追加结果页提示（控制台命令翻译说明等调用方补充文案）。
     * notice 已有内容时合并为一行（追加在前，注入层提示在后）；extra 为空返回自身。
     */
    public QueryResult withNotice(String extra) {
        if (extra == null || extra.isBlank()) {
            return this;
        }
        String merged = (notice == null || notice.isBlank()) ? extra : extra + "；" + notice;
        return new QueryResult(success, reason, columns, rows, rowCount, elapsedMs, truncated,
                instanceId, sql, rewrittenSql, originalLimit, injected, tightened, merged);
    }
}
