package com.sqlaudit.parser.mysql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlOutFileExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.AbstractDruidParser;

/**
 * MySQL 8.0 解析器（README §4.3：Druid SQL Parser → SQLStatement → Visitor）。
 *
 * <p>覆盖：DDL / DML / 部分 DCL。解析失败 → 高危人工审（铁律在基类）。</p>
 */
public class MySqlDialectParser extends AbstractDruidParser {

    @Override
    protected DbType dbType() {
        return DbType.mysql;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    protected SQLASTVisitor createCollectorVisitor(AstSink sink) {
        return new MySqlAstCollector(sink);
    }

    @Override
    protected void checkDialectSelectFlags(ParsedStatement ps, SQLSelectQueryBlock block) {
        // README §4.3 第 2 行：MySQL 5.7 形态的 LOCK IN SHARE MODE（等价 FOR SHARE）
        if (block instanceof MySqlSelectQueryBlock mysql && mysql.isLockInShareMode()) {
            ps.getDangerousClauses().add(DangerousClause.FOR_UPDATE_LOCK);
        }
        // README §4.3 第 3 行：SELECT ... INTO @var（写会话状态；诊断实测 getInto() = @v）
        if (block.getInto() != null) {
            ps.getDangerousClauses().add(DangerousClause.INTO_OUTFILE);
        }
        // INTO OUTFILE 由 MySqlOutFileExpr 节点检测（见 MySqlAstCollector）；
        // INTO DUMPFILE Druid 1.2.23 解析失败 → 基类铁律兜底为 LOW_CONFIDENCE 高危
    }

    /**
     * MySQL 方言 AST 收集器。
     * <p><strong>必须继承 MySqlASTVisitorAdapter</strong>：MySQL 方言节点（如
     * MySqlOutFileExpr extends MySqlObjectImpl）在 accept 普通 SQLASTVisitor 时
     * 直接抛 IllegalArgumentException（诊断实测），会导致语句被误判为解析失败。</p>
     */
    static final class MySqlAstCollector extends MySqlASTVisitorAdapter {

        private final AstSink sink;

        MySqlAstCollector(AstSink sink) {
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

        /** README §4.3 第 3 行：SELECT ... INTO OUTFILE（写文件）—— AST 真实节点。 */
        @Override
        public boolean visit(MySqlOutFileExpr x) {
            sink.addDangerous(DangerousClause.INTO_OUTFILE);
            return true;
        }
    }
}
