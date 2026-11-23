package com.sqlaudit.rollback.preimage;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.rule.TableMetadataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 前镜像 SELECT 构造器（README §4.4）。
 *
 * <p><strong>生成时机铁律</strong>：构造出的 SELECT 在 Worker 执行时刻、与原 DML
 * 同一 Connection、同一事务内执行（{@code FOR UPDATE}），本类只产出 SQL 文本，
 * 绝不在审核期执行。</p>
 *
 * <p>列集合规则（README §4.4）：</p>
 * <ul>
 *   <li>UPDATE 前镜像 = 主键列 + SET 列（只取回滚所需的旧值，不是全表列）；</li>
 *   <li>DELETE 前镜像 = 全部非生成列（反向 INSERT 需要整行旧值；
 *       生成列绝不能出现在列清单里，否则反向 INSERT 报
 *       「The value specified for generated column ... is not allowed」）；</li>
 *   <li>WHERE 与原语句完全一致（复用原 WHERE AST 的克隆，不重新构造，
 *       避免任何语义偏移）。带 LIMIT/ORDER BY 的 DML 已被 R_LIMIT_ORDER_DML 阻断，
 *       不存在需要复现 LIMIT 的场景（README §4.4 修正）。</li>
 * </ul>
 */
public class PreimagePlanner {

    /**
     * 前镜像查询（带列序——反向 SQL 的参数顺序与此对齐）。
     *
     * @param sqlText  前镜像 SELECT 文本（含 FOR UPDATE）
     * @param columns  列清单（顺序即 ResultSet 读取顺序，排除生成列）
     */
    public record PreimageQuery(String sqlText, List<String> columns) {
    }

    /**
     * 为 UPDATE / DELETE 构造前镜像 SELECT。
     *
     * @throws IllegalArgumentException 语句形态不支持（应由能力评估器先行过滤）
     */
    public PreimageQuery plan(ParsedStatement ps, TableMetadataProvider metadata) {
        if (ps.getStatementType() != StatementType.UPDATE && ps.getStatementType() != StatementType.DELETE) {
            throw new IllegalArgumentException("前镜像仅支持 UPDATE/DELETE: " + ps.getStatementType());
        }
        Object src = ps.getSourceStatement();
        if (!(src instanceof SQLUpdateStatement update)) {
            throw new IllegalArgumentException("前镜像需要 Druid UPDATE AST（DELETE 的构造见 planDelete）");
        }
        return planUpdate(update, metadata, ps.getDialect());
    }

    /** UPDATE 前镜像：SELECT pk, set_cols... FROM t WHERE <原 WHERE> FOR UPDATE。 */
    public PreimageQuery planUpdate(SQLUpdateStatement update,
                                    TableMetadataProvider metadata, Dialect dialect) {
        SQLExprTableSource table = tableOf(update.getTableSource());
        TableMetadataProvider.TableMetadata tm = metadata.getTable(table.getSchema(), table.getTableName());
        if (tm == null || tm.primaryKeyColumns().isEmpty()) {
            throw new IllegalArgumentException("目标表无主键元数据: " + table.getTableName());
        }

        // 列序：主键在前 + SET 列（去重保序）
        List<String> columns = new ArrayList<>(tm.primaryKeyColumns());
        for (SQLUpdateSetItem item : update.getItems()) {
            String col = columnName(item.getColumn());
            if (!columns.contains(col)) {
                columns.add(col);
            }
        }

        String sql = buildSelect(table, columns, update.getWhere(), dialect);
        return new PreimageQuery(sql, columns);
    }

    /** DELETE 前镜像：SELECT 全部非生成列 FROM t WHERE <原 WHERE> FOR UPDATE（反向 INSERT 需整行）。 */
    public PreimageQuery planDelete(com.alibaba.druid.sql.ast.statement.SQLDeleteStatement delete,
                                    TableMetadataProvider metadata, Dialect dialect) {
        SQLExprTableSource table = tableOf(delete.getExprTableSource() != null
                ? delete.getExprTableSource() : delete.getTableSource());
        TableMetadataProvider.TableMetadata tm = metadata.getTable(table.getSchema(), table.getTableName());
        if (tm == null || tm.primaryKeyColumns().isEmpty()) {
            throw new IllegalArgumentException("目标表无主键元数据: " + table.getTableName());
        }

        // 全部非生成列（生成列绝不能进反向 INSERT 的列清单，README §4.4）
        List<String> columns = new ArrayList<>();
        for (String col : tm.columns()) {
            if (!tm.generatedColumns().contains(col)) {
                columns.add(col);
            }
        }

        String sql = buildSelect(table, columns, delete.getWhere(), dialect);
        return new PreimageQuery(sql, columns);
    }

    // ------------------------------------------------------------------

    private String buildSelect(SQLExprTableSource table, List<String> columns,
                               com.alibaba.druid.sql.ast.SQLExpr where, Dialect dialect) {
        DbType dbType = dialect == Dialect.PG ? DbType.postgresql : DbType.mysql;
        SQLSelectQueryBlock qb;
        if (dbType == DbType.mysql) {
            qb = new com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock();
            qb.setForUpdate(true); // MySQL：基类字段输出有效
        } else {
            var pg = new com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock();
            // PG 方言陷阱（实测）：FOR UPDATE 必须走 forClause，基类 setForUpdate 对
            // PG 输出 visitor 无效（输出会丢 FOR UPDATE）
            var forClause = new com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock.ForClause();
            forClause.setOption(com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock.ForClause.Option.UPDATE);
            pg.setForClause(forClause);
            qb = pg;
        }
        qb.setDbType(dbType);
        qb.setFrom(table.clone());
        for (String col : columns) {
            qb.addSelectItem(new com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr(col));
        }
        if (where != null) {
            qb.setWhere(where.clone());
        }
        SQLSelectStatement stmt = new SQLSelectStatement(new com.alibaba.druid.sql.ast.statement.SQLSelect(qb), dbType);
        return com.alibaba.druid.sql.SQLUtils.toSQLString(stmt, dbType).trim();
    }

    /** getTableSource() 返回基类 SQLTableSource，多表 JOIN 已被上游规则阻断，此处收敛为单表。 */
    private SQLExprTableSource tableOf(com.alibaba.druid.sql.ast.statement.SQLTableSource ts) {
        if (ts instanceof SQLExprTableSource expr && expr.getTableName() != null) {
            return expr;
        }
        throw new IllegalArgumentException("无法识别目标表（多表 JOIN 应已被 R_MULTI_TABLE_DML 阻断）");
    }

    private String columnName(com.alibaba.druid.sql.ast.SQLExpr columnExpr) {
        if (columnExpr instanceof SQLName name) {
            return name.getSimpleName();
        }
        throw new IllegalArgumentException("SET 列不是简单列名: " + columnExpr);
    }
}
