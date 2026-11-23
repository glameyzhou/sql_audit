package com.sqlaudit.parser.postgres;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.AbstractDruidParser;

/**
 * PostgreSQL 14+ 解析器（README §4.3）。
 *
 * <p><strong>临时实现</strong>：主方案是 libpg_query 的 Java 绑定（真实 PG 语法树），
 * 是否引入取决于 P0 spike S1 的结论。当前用 Druid PG 模式承担：
 * <ul>
 *   <li>能完整解析的语句：正常归一化 + AST 只读判定；</li>
 *   <li>解析失败的语句：confidence=0 高危人工审（铁律）——不会「解析失败就放行」。</li>
 * </ul>
 * spike 结论落地后，若换 libpg_query，本类的对外行为契约（ParsedStatement）不变。</p>
 */
public class PostgresDialectParser extends AbstractDruidParser {

    @Override
    protected DbType dbType() {
        return DbType.postgresql;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.PG;
    }

    @Override
    protected SQLASTVisitor createCollectorVisitor(AstSink sink) {
        return new PGAstCollector(sink);
    }

    @Override
    protected void checkDialectSelectFlags(ParsedStatement ps, SQLSelectQueryBlock block) {
        // README §4.3 第 2 行：PG 的 FOR UPDATE / FOR SHARE 存在 forClause（诊断实测：
        // 基类 isForUpdate()/isForShare() 对 PG 恒为 false——Druid PG 解析器不填充）
        if (block instanceof PGSelectQueryBlock pg && pg.getForClause() != null) {
            ps.getDangerousClauses().add(DangerousClause.FOR_UPDATE_LOCK);
        }
        // README §4.3 第 4 行：PG SELECT ... INTO new_table（实际建表写数据）
        if (block.getInto() != null) {
            ps.getDangerousClauses().add(DangerousClause.SELECT_INTO);
        }
    }

    /** PG 方言 AST 收集器（用 PGASTVisitorAdapter 以兼容 PG 方言节点）。 */
    static final class PGAstCollector extends PGASTVisitorAdapter {

        private final AstSink sink;

        PGAstCollector(AstSink sink) {
            this.sink = sink;
        }

        @Override
        public boolean visit(SQLWithSubqueryClause.Entry entry) {
            sink.onEntry(entry);
            return true;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            sink.onTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLMethodInvokeExpr x) {
            sink.onFunction(x.getMethodName());
            return true;
        }

        @Override
        public boolean visit(SQLQueryExpr x) {
            sink.markSubquery();
            return true;
        }

        @Override
        public boolean visit(SQLInSubQueryExpr x) {
            sink.markSubquery();
            return true;
        }

        @Override
        public boolean visit(SQLExistsExpr x) {
            sink.markSubquery();
            return true;
        }

        @Override
        public boolean visit(SQLSubqueryTableSource x) {
            sink.markSubquery();
            return true;
        }
    }
}
