package com.sqlaudit.query.limit;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.sqlaudit.common.enums.Dialect;

import java.util.List;
import java.util.Objects;

/**
 * AST 注入 / 收紧 LIMIT（README §4.1 三层保护第 1 层：语义正确层）。
 *
 * <p><strong>LIMIT 注入必须走 AST，禁止字符串拼接</strong>（README §4.1 修正）。
 * 字符串追加的三种出错 case：</p>
 * <ol>
 *   <li>{@code SELECT ... LIMIT 10} + 拼接 → {@code LIMIT 10 LIMIT 1000} 语法错误；</li>
 *   <li>UNION 上拼接 → LIMIT 只作用于最后一个分支，语义被改变；</li>
 *   <li>外层包裹 {@code SELECT * FROM (...) LIMIT n} → 子查询重名列（t1.id / t2.id）
 *       报 Duplicate column name。</li>
 * </ol>
 *
 * <p>本实现直接在原 AST 上 setLimit，天然规避 case 3（不包裹、不改列名）；
 * case 1 由「读已有 limit 再决定注入/收紧」规避；case 2 由「LIMIT 作用在最外层
 * 查询节点」规避。</p>
 *
 * <p><strong>Druid 实测事实</strong>（诊断测试验证后删除，MySQL / PG 行为一致）：</p>
 * <ul>
 *   <li>普通 SELECT 的 LIMIT 在 {@code SQLSelectQueryBlock.getLimit()}，
 *       union 顶层的 LIMIT 在 {@code SQLUnionQuery.getLimit()}（分支 block 上为 null），
 *       带括号的分支 LIMIT 才在分支 block 上；</li>
 *   <li>两种节点都有 {@code setLimit(SQLLimit)}，输出用
 *       {@code SQLUtils.toSQLString(stmt, dbType)} 正确还原（含 MySQL/PG 方言节点）；</li>
 *   <li>{@code SQLSelect.getQueryBlock()} 在顶层是 UNION 时返回 null，不能用它取 limit。</li>
 * </ul>
 *
 * <p>查询通道只处理 SELECT（只读判定已在门禁做过）；非 SELECT（含 EXPLAIN）、
 * IoTDB 方言（白名单 parser 不产 Druid AST）、解析失败、多语句 → {@code unsupported}，
 * 不抛异常，调用方走 setMaxRows + 流式 cancel 兜底。</p>
 */
public final class LimitInjector {

    /**
     * 注入 / 收紧 LIMIT。
     *
     * @param sql      单条 SELECT 语句（由 QueryGate 保证只读单语句）
     * @param dialect  目标方言
     * @param maxLimit 平台行数上限
     * @return 注入结果，见 {@link InjectionResult}
     */
    public InjectionResult inject(String sql, Dialect dialect, int maxLimit) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(dialect, "dialect");
        if (dialect == Dialect.IOTDB) {
            // IoTDB 白名单 parser 不产 Druid AST，无 LIMIT 语法可注入 → 兜底层
            return InjectionResult.unsupported(sql);
        }

        List<SQLStatement> statements;
        try {
            SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(sql, dbTypeOf(dialect));
            statements = parser.parseStatementList();
        } catch (Exception e) {
            return InjectionResult.unsupported(sql);
        }
        // 单语句通道（门禁已挡多语句，这里双保险）
        if (statements.size() != 1) {
            return InjectionResult.unsupported(sql);
        }
        SQLStatement stmt = statements.get(0);
        // 只处理 SELECT：EXPLAIN 等只读但非 SELECT 的语句交由兜底层截断
        if (!(stmt instanceof SQLSelectStatement select)) {
            return InjectionResult.unsupported(sql);
        }

        SQLLimit limit = limitOf(select.getSelect().getQuery());
        if (limit == null) {
            // 无 LIMIT → 注入上限（在原 AST 上 setLimit，不包裹外层查询）
            setLimit(select.getSelect().getQuery(), new SQLLimit(maxLimit));
            return new InjectionResult(
                    SQLUtils.toSQLString(stmt, dbTypeOf(dialect)).trim(),
                    null, maxLimit, true, false, false);
        }

        Integer rowCount = readIntegerRowCount(limit);
        if (rowCount == null) {
            // 行数非整数字面量（如 LIMIT ? / LIMIT ALL）：无法判定是否超限，
            // 不改写（避免误伤），交由第 2、3 层兜底
            return InjectionResult.passthrough(sql, null);
        }
        if (rowCount <= maxLimit) {
            // 用户 LIMIT 在上限内 → 不动（README §4.1：已有且 ≤ 上限 → 不动）
            return InjectionResult.passthrough(sql, rowCount);
        }
        // 收紧：只改行数，保留用户 offset（MySQL LIMIT offset, count 形态）
        limit.setRowCount(new SQLIntegerExpr(maxLimit));
        return new InjectionResult(
                SQLUtils.toSQLString(stmt, dbTypeOf(dialect)).trim(),
                rowCount, maxLimit, false, true, false);
    }

    /** 取最外层查询节点的 LIMIT：UNION 取整体（SQLUnionQuery），普通查询取 block。 */
    private SQLLimit limitOf(SQLSelectQuery query) {
        if (query instanceof SQLUnionQuery union) {
            return union.getLimit();
        }
        if (query instanceof SQLSelectQueryBlock block) {
            return block.getLimit();
        }
        return null;
    }

    /** 在最外层查询节点上设置 LIMIT。 */
    private void setLimit(SQLSelectQuery query, SQLLimit limit) {
        if (query instanceof SQLUnionQuery union) {
            union.setLimit(limit);
        } else if (query instanceof SQLSelectQueryBlock block) {
            block.setLimit(limit);
        }
    }

    /** 读取 LIMIT 行数；仅整数字面量可判定，其余返回 null。 */
    private Integer readIntegerRowCount(SQLLimit limit) {
        if (limit.getRowCount() instanceof SQLIntegerExpr expr) {
            Number n = expr.getNumber();
            return n == null ? null : n.intValue();
        }
        return null;
    }

    private DbType dbTypeOf(Dialect dialect) {
        return dialect == Dialect.MYSQL ? DbType.mysql : DbType.postgresql;
    }
}
