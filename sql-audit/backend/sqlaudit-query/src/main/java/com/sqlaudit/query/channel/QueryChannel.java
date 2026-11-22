package com.sqlaudit.query.channel;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.query.gate.GateResult;
import com.sqlaudit.query.gate.QueryGate;
import com.sqlaudit.query.limit.InjectionResult;
import com.sqlaudit.query.limit.LimitInjector;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 查询通道执行编排（设计文档 §6.2：门禁 → LIMIT 注入 → 只读账号流式执行）。
 *
 * <p><strong>三层保护缺一不可</strong>（§6.2）：</p>
 * <ol>
 *   <li><strong>AST 注入 LIMIT</strong>（语义正确层，{@link LimitInjector}）：无 LIMIT 注入上限，
 *       过大收紧；边缘语法解析不了时降级；</li>
 *   <li><strong>{@code Statement.setMaxRows(maxLimit + 1)}</strong>（驱动兜底层）：AST 注入失败
 *       仍截断。取 maxLimit+1 而非 maxLimit：多读 1 行用于精确探测截断（第 1 层降级、
 *       SQL 里没有 LIMIT 时，读到第 maxLimit+1 行 → truncated=true），返回给用户的仍是最多
 *       maxLimit 行；</li>
 *   <li><strong>流式读取 + {@code Statement.cancel()}</strong>（停止服务端扫描层）：前两层只管
 *       结果集大小，这一层在读满上限后通知服务端停止继续扫描。</li>
 * </ol>
 *
 * <p><strong>truncated 语义</strong>：true 表示结果可能被平台截断——
 * ①流式读到第 maxLimit+1 行（第 1 层降级时的精确探测）；
 * ②平台 LIMIT（注入/收紧）生效且恰好读满 maxLimit 行（数据库因 LIMIT 停止返回，
 * 无法区分「正好 maxLimit 行」与「还有更多行」，保守置 true）。
 * 用户自己的 LIMIT（≤ 上限，原样透传）导致的取数不算平台截断。</p>
 *
 * <p>超时：{@code setQueryTimeout}（§6.2：驱动层超时；PG 生产侧另发
 * {@code SET LOCAL statement_timeout}，MySQL 另加 MAX_EXECUTION_TIME hint，由 storage
 * 连接提供方在 W2 集成时落地，本层 JDBC 超时已生效）。</p>
 *
 * <p>结果集不落库（§6.2）；{@link QueryResult} 带齐 query_log 落库所需字段。
 * 行/总字节上限校验为 TODO（W2 掩码/导出阶段实现，参数已在 {@link QueryOptions} 预留）。</p>
 */
public final class QueryChannel {

    private final ConnectionProvider connectionProvider;
    private final QueryOptions options;
    private final QueryGate gate;
    private final LimitInjector limitInjector;

    public QueryChannel(ConnectionProvider connectionProvider, QueryOptions options) {
        this(connectionProvider, options, new QueryGate(), new LimitInjector());
    }

    public QueryChannel(ConnectionProvider connectionProvider, QueryOptions options,
                        QueryGate gate, LimitInjector limitInjector) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.options = Objects.requireNonNull(options, "options");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.limitInjector = Objects.requireNonNull(limitInjector, "limitInjector");
    }

    /**
     * 执行一次查询（门禁 → LIMIT 注入 → 三层保护下执行）。
     *
     * <p>门禁拒绝 / 执行异常均不抛出，以 {@link QueryResult#isSuccess()}=false + reason 返回，
     * 调用方（W2 web 层）据此生成 query_log 与用户提示。</p>
     *
     * @param instanceId 目标实例（由 ConnectionProvider 路由到只读连接）
     * @param sql        用户 SQL（单语句）
     * @param dialect    目标方言
     * @return 执行结果
     */
    public QueryResult execute(long instanceId, String sql, Dialect dialect) {
        long startNanos = System.nanoTime();

        // 门禁：只读单语句判定（复用 parser 契约）
        GateResult gateResult = gate.check(sql, dialect);
        if (!gateResult.allowed()) {
            return QueryResult.rejected(instanceId, sql, gateResult.reason(), elapsedMs(startNanos));
        }

        // 第 1 层：AST 注入 / 收紧 LIMIT（unsupported 时原样透传，由第 2、3 层兜底）
        InjectionResult injection = limitInjector.inject(sql, dialect, options.maxLimit());
        String execSql = injection.rewrittenSql();

        try (Connection conn = connectionProvider.getConnection(instanceId);
             Statement stmt = conn.createStatement()) {

            // 第 2 层：驱动层兜底截断（maxLimit+1 行 = maxLimit 结果行 + 1 探测行）
            stmt.setMaxRows(options.maxLimit() + 1);
            stmt.setQueryTimeout(options.queryTimeoutSeconds());
            // 流式拉取：避免驱动一次性物化整个结果集
            stmt.setFetchSize(Math.min(500, options.maxLimit()));

            if (!stmt.execute(execSql)) {
                // 门禁只放行 SELECT，理论上不会走到；防御性返回空结果
                return QueryResult.success(instanceId, sql, execSql,
                        injection.originalLimit(), injection.injected(), injection.tightened(),
                        injection.notice(), List.of(), List.of(), elapsedMs(startNanos), false);
            }

            try (ResultSet rs = stmt.getResultSet()) {
                List<String> columns = readColumns(rs.getMetaData());
                List<List<Object>> rows = new ArrayList<>();
                boolean overflowObserved = false;
                while (rs.next()) {
                    if (rows.size() == options.maxLimit()) {
                        // 读到第 maxLimit+1 行：结果集确定超上限（第 1 层降级路径的精确探测）
                        overflowObserved = true;
                        // 第 3 层：通知服务端停止继续扫描（前两层只管结果集大小）
                        // cancel 失败不影响已取数据，静默忽略
                        try {
                            stmt.cancel();
                        } catch (SQLException ignore) {
                            // 连接/语句已关闭等服务端竞态，无需处理
                        }
                        break;
                    }
                    rows.add(readRow(rs, columns.size()));
                }
                // 平台 LIMIT（注入/收紧）生效且读满上限：数据库因 LIMIT 停止返回，
                // 无法区分「正好 maxLimit 行」与「还有更多」→ 保守判为可能截断
                boolean platformLimitInEffect = injection.injected() || injection.tightened();
                boolean truncated = overflowObserved
                        || (platformLimitInEffect && rows.size() == options.maxLimit());
                return QueryResult.success(instanceId, sql, execSql,
                        injection.originalLimit(), injection.injected(), injection.tightened(),
                        injection.notice(), columns, rows, elapsedMs(startNanos), truncated);
            }
        } catch (SQLException e) {
            return QueryResult.failed(instanceId, sql,
                    "查询执行失败: " + e.getMessage(), elapsedMs(startNanos));
        }
    }

    private List<String> readColumns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<String> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        return columns;
    }

    private List<Object> readRow(ResultSet rs, int columnCount) throws SQLException {
        List<Object> row = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            row.add(rs.getObject(i));
        }
        return row;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
