package com.sqlaudit.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLExplainStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.SqlObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Druid 解析公共实现（设计文档 §4.1 / §4.2）。
 *
 * <p>MySQL 与 PG 共用本类（DbType 不同）；PG 的完整语法树依赖 S1 spike
 * （libpg_query 绑定）结论，当前以 Druid PG 模式 + 解析失败一律高危作为临时实现。</p>
 *
 * <p><strong>只读判定只走 AST，不用正则</strong>（§4.2；原型里的 lintSql 是演示用的，禁止抄进后端）：
 * 危险子句逐条对应 §4.2 表的每一行，A-1 验收要求每行都有测试用例。</p>
 *
 * <p>解析失败铁律：confidence=0 + LOW_CONFIDENCE + readOnly=false，绝不降级放行。</p>
 *
 * <p><strong>visitor 必须按方言选择</strong>（实现教训）：Druid 的 MySQL 方言 AST 节点
 * （如 {@code MySqlOutFileExpr}）在 accept 普通 {@code SQLASTVisitor} 时直接抛
 * {@code IllegalArgumentException: not support visitor type}——
 * MySQL 必须用 {@code MySqlASTVisitorAdapter}，PG 用 {@code PGASTVisitorAdapter}，
 * 见 {@link #createCollectorVisitor(AstSink)}。</p>
 */
public abstract class AbstractDruidParser implements DialectParser {

    protected abstract DbType dbType();

    protected abstract Dialect dialect();

    /**
     * 按方言创建 AST 收集器（遍历用 visitor 的方言适配器）。
     * <p>MySQL 方言节点要求 MySqlASTVisitor，PG 方言节点要求 PGASTVisitor，
     * 普通 SQLASTVisitorAdapter 会在遍历到方言节点时抛异常。</p>
     */
    protected abstract SQLASTVisitor createCollectorVisitor(AstSink sink);

    @Override
    public List<ParsedStatement> parse(String sql) {
        Objects.requireNonNull(sql, "sql");
        try {
            SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(sql, dbType());
            List<SQLStatement> statements = parser.parseStatementList();
            if (statements.isEmpty()) {
                return List.of(failed(sql));
            }
            List<ParsedStatement> result = new ArrayList<>(statements.size());
            for (SQLStatement stmt : statements) {
                result.add(normalize(stmt));
            }
            return result;
        } catch (Exception e) {
            // 解析失败 = 高危人工审（§4.1 铁律），整体按单条失败语句处理
            return List.of(failed(sql));
        }
    }

    private ParsedStatement failed(String sql) {
        ParsedStatement ps = new ParsedStatement();
        ps.setDialect(dialect());
        ps.setStatementType(StatementType.OTHER);
        ps.setOriginalSql(sql);
        ps.setNormalizedSql(sql);
        ps.setParseConfidence(0);
        ps.setReadOnly(false);
        ps.getDangerousClauses().add(DangerousClause.LOW_CONFIDENCE);
        return ps;
    }

    // ------------------------------------------------------------------
    // 归一化：SQLStatement → ParsedStatement
    // ------------------------------------------------------------------

    private ParsedStatement normalize(SQLStatement stmt) {
        ParsedStatement ps = new ParsedStatement();
        ps.setDialect(dialect());
        ps.setOriginalSql(SQLUtils.toSQLString(stmt, dbType()));
        ps.setNormalizedSql(ps.getOriginalSql());
        ps.setParseConfidence(100);

        // EXPLAIN / EXPLAIN ANALYZE（§4.2 第 1 行：ANALYZE 会真实执行，必须非只读）
        SQLStatement target = stmt;
        if (stmt instanceof SQLExplainStatement explain) {
            if (isExplainAnalyze(explain)) {
                ps.getDangerousClauses().add(DangerousClause.EXPLAIN_ANALYZE);
            }
            // 普通 EXPLAIN 只读（§4.4：dry-run 只用不带 ANALYZE 的 EXPLAIN）
            SQLStatement inner = explain.getStatement();
            if (inner != null) {
                target = inner;
            }
        }

        // 会话级语句（§4.2 第 7 行）：SET / CALL / LOCK TABLES / USE → SESSION_MUTATION
        if (target instanceof SQLSetStatement || isSessionMutation(target)) {
            ps.setStatementType(StatementType.OTHER);
            ps.getDangerousClauses().add(DangerousClause.SESSION_MUTATION);
            return ps;
        }

        // 语句类型
        StatementType type = resolveStatementType(target);
        ps.setStatementType(type);

        // AST 收集：表名 / 函数调用 / 子查询 / CTE（visitor 按方言选择）
        AstSink sink = new AstSink();
        target.accept(createCollectorVisitor(sink));
        ps.setObjects(new ArrayList<>(sink.tables));
        ps.setContainsSubquery(sink.hasSubquery);
        ps.setContainsFunction(!sink.functionNames.isEmpty());
        ps.getDangerousClauses().addAll(sink.dangerous);

        // SELECT 锁定 / INTO 形态检查（§4.2 第 2、3、4 行），UNION 递归到每个分支
        if (target instanceof SQLSelectStatement select) {
            checkSelectDangerous(ps, select.getSelect().getQuery());
        }

        // WHERE / LIMIT AST 节点 + 原始语句节点（供 rule/rollback 模块下游使用）
        attachWhereAndLimit(ps, target);
        ps.setSourceStatement(target);

        // 只读判定（§4.2 白名单）：SELECT + 无危险子句 + 置信度满
        ps.setReadOnly(type == StatementType.SELECT
                && ps.getDangerousClauses().isEmpty()
                && ps.getParseConfidence() >= 100);
        return ps;
    }

    /**
     * EXPLAIN ANALYZE 检测（诊断实测）：Druid 把 ANALYZE 存在 {@code type} 字段。
     * <p>普通 EXPLAIN 的 type 为 null（只读，§4.4 dry-run 允许）；ANALYZE 真实执行，非只读。</p>
     */
    protected boolean isExplainAnalyze(SQLExplainStatement explain) {
        return "ANALYZE".equalsIgnoreCase(explain.getType());
    }

    /** 各方言可覆写：CALL / LOCK TABLES / USE 等会话级语句判定。 */
    protected boolean isSessionMutation(SQLStatement stmt) {
        String name = stmt.getClass().getSimpleName();
        return name.startsWith("SQLCall")
                // SQLLockTableStatement（基类）与 MySqlLockTableStatement（方言类）
                || name.contains("LockTable")
                || name.endsWith("UseStatement");
    }

    /** 方言钩子：SELECT 语句方言特有形态（MySQL 的 LOCK IN SHARE MODE 等）。 */
    protected void checkDialectSelectFlags(ParsedStatement ps, SQLSelectQueryBlock block) {
        // 默认无方言特有形态
    }

    /** 递归检查 UNION 每个分支的 FOR UPDATE / FOR SHARE。 */
    private void checkSelectDangerous(ParsedStatement ps, SQLSelectQuery query) {
        if (query instanceof SQLUnionQuery union) {
            checkSelectDangerous(ps, union.getLeft());
            checkSelectDangerous(ps, union.getRight());
            return;
        }
        if (query instanceof SQLSelectQueryBlock block) {
            // §4.2 第 2 行：加行锁，影响线上（PG 的 FOR UPDATE 在 forClause，见方言钩子）
            if (block.isForUpdate() || block.isForShare()) {
                ps.getDangerousClauses().add(DangerousClause.FOR_UPDATE_LOCK);
            }
            checkDialectSelectFlags(ps, block);
        }
    }

    private StatementType resolveStatementType(SQLStatement stmt) {
        if (stmt instanceof SQLSelectStatement) {
            return StatementType.SELECT;
        }
        if (stmt instanceof SQLInsertStatement) {
            return StatementType.INSERT;
        }
        // REPLACE：insert-or-replace 语义，归 INSERT 家族（rule 的 R_NO_PK 等同样适用）
        if (stmt instanceof com.alibaba.druid.sql.ast.statement.SQLReplaceStatement) {
            return StatementType.INSERT;
        }
        if (stmt instanceof SQLUpdateStatement) {
            return StatementType.UPDATE;
        }
        if (stmt instanceof SQLDeleteStatement) {
            return StatementType.DELETE;
        }
        // DDL：SQLDDLStatement 及 truncate / create database / drop database 等补充
        if (stmt instanceof com.alibaba.druid.sql.ast.statement.SQLDDLStatement
                || stmt instanceof com.alibaba.druid.sql.ast.statement.SQLCreateDatabaseStatement
                || stmt instanceof com.alibaba.druid.sql.ast.statement.SQLTruncateStatement
                || stmt instanceof com.alibaba.druid.sql.ast.statement.SQLDropDatabaseStatement) {
            return StatementType.DDL;
        }
        return StatementType.OTHER;
    }

    private void attachWhereAndLimit(ParsedStatement ps, SQLStatement target) {
        if (target instanceof SQLSelectStatement select) {
            SQLSelectQueryBlock queryBlock = select.getSelect().getQueryBlock();
            if (queryBlock != null) {
                ps.setWhereAst(queryBlock.getWhere());
                ps.setLimitAst(queryBlock.getLimit());
            }
        } else if (target instanceof SQLUpdateStatement update) {
            ps.setWhereAst(update.getWhere());
        } else if (target instanceof SQLDeleteStatement delete) {
            ps.setWhereAst(delete.getWhere());
        }
    }

    // ------------------------------------------------------------------
    // AST 收集：状态与共享逻辑（方言 visitor 适配器把节点转发进来）
    // ------------------------------------------------------------------

    /**
     * AST 收集结果与共享判定逻辑（方言无关）。
     * <p>注意 CTE 别名表（{@code WITH x AS (...) SELECT * FROM x} 里的 x）
     * 不算真实对象：先在 onEntry 注册 CTE 名，onTableSource 排除。</p>
     */
    public static final class AstSink {

        final List<SqlObject> tables = new ArrayList<>();
        final List<String> functionNames = new ArrayList<>();
        final List<DangerousClause> dangerous = new ArrayList<>();
        boolean hasSubquery;

        private final List<String> cteNames = new ArrayList<>();

        public void onEntry(SQLWithSubqueryClause.Entry entry) {
            // CTE 别名注册（诊断实测：别名在 getAlias()，getExpr() 恒为 null）
            String alias = entry.getAlias();
            if (alias != null) {
                cteNames.add(alias.toLowerCase());
            }
            // 数据修改 CTE（§4.2 第 5 行）：WITH x AS (DELETE/UPDATE/INSERT ... RETURNING *)
            // —— 实际写表。Druid 把 CTE 内的 DML 存在 returningStatement 里
            if (entry.getReturningStatement() != null) {
                dangerous.add(DangerousClause.DATA_MODIFYING_CTE);
            }
        }

        public void onTableSource(SQLExprTableSource x) {
            String tableName = x.getTableName();
            if (tableName != null && !cteNames.contains(tableName.toLowerCase())) {
                String schema = x.getSchema();
                tables.add(new SqlObject(schema, tableName, "TABLE"));
            }
        }

        public void onFunction(String methodName) {
            if (methodName != null) {
                functionNames.add(methodName);
                // 函数副作用（§4.2 第 6 行）：非白名单函数一律保守判为可能写表
                if (!FunctionWhitelist.isPure(methodName)) {
                    dangerous.add(DangerousClause.FUNCTION_SIDE_EFFECT);
                }
            }
        }

        public void markSubquery() {
            hasSubquery = true;
        }

        /** 方言特有危险节点直接追加（如 MySqlOutFileExpr）。 */
        public void addDangerous(DangerousClause clause) {
            dangerous.add(clause);
        }
    }
}
