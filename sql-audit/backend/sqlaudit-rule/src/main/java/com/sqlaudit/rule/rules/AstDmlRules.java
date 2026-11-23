package com.sqlaudit.rule.rules;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLNCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLNullExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.rule.BuiltInRules;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DML 的 AST 规则组（设计文档 §4.3）。
 *
 * <p>R_NO_WHERE / R_LIMIT_ORDER_DML / R_NONDETERMINISTIC_SET / R_MULTI_TABLE_DML /
 * R_SELECT_STAR。全部基于 parser 挂载的 sourceStatement（Druid AST）判定。</p>
 */
public final class AstDmlRules {

    private AstDmlRules() {
    }

    private static RuleDefinition def(RuleCode code) {
        return BuiltInRules.byCode(code);
    }

    /** R_NO_WHERE：UPDATE/DELETE 无 WHERE → ERROR。 */
    public static Rule noWhere() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_NO_WHERE);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.UPDATE && ps.getStatementType() != StatementType.DELETE) {
                    return List.of();
                }
                if (ps.getWhereAst() == null) {
                    return List.of(hit("UPDATE/DELETE 缺少 WHERE 条件"));
                }
                return List.of();
            }
        };
    }

    /** R_LIMIT_ORDER_DML：UPDATE/DELETE 带 LIMIT/ORDER BY（回滚不可靠）→ ERROR。 */
    public static Rule limitOrderDml() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_LIMIT_ORDER_DML);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.UPDATE && ps.getStatementType() != StatementType.DELETE) {
                    return List.of();
                }
                if (!(ps.getSourceStatement() instanceof SQLUpdateStatement update)) {
                    // DELETE 的 LIMIT 在方言类 MySqlDeleteStatement（基类无 getLimit，诊断实测）
                    if (ps.getSourceStatement() instanceof com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlDeleteStatement del
                            && del.getLimit() != null) {
                        return List.of(hit("DELETE 带 LIMIT，回滚不可靠",
                                Map.of("clause", "LIMIT")));
                    }
                    return List.of();
                }
                List<String> clauses = new ArrayList<>();
                if (update.getLimit() != null) {
                    clauses.add("LIMIT");
                }
                if (update instanceof MySqlUpdateStatement mysql && mysql.getOrderBy() != null) {
                    clauses.add("ORDER BY");
                }
                if (!clauses.isEmpty()) {
                    return List.of(hit("UPDATE 带 " + String.join("/", clauses) + "，回滚不可靠",
                            Map.of("clauses", clauses)));
                }
                return List.of();
            }
        };
    }

    /** R_NONDETERMINISTIC_SET：SET 值非确定性字面量（c+1 / NOW() / 子查询）→ ERROR，标不可回滚。 */
    public static Rule nondeterministicSet() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_NONDETERMINISTIC_SET);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.UPDATE
                        || !(ps.getSourceStatement() instanceof SQLUpdateStatement update)) {
                    return List.of();
                }
                List<String> badItems = new ArrayList<>();
                for (SQLUpdateSetItem item : update.getItems()) {
                    if (!isDeterministicLiteral(item.getValue())) {
                        badItems.add(String.valueOf(item.getColumn()));
                    }
                }
                if (!badItems.isEmpty()) {
                    return List.of(hit("SET 值含非确定性表达式（前镜像失效，不可回滚）: "
                            + String.join(", ", badItems), Map.of("columns", badItems)));
                }
                return List.of();
            }

            /**
             * 确定性字面量判定：仅数字/字符串/NULL/布尔字面量。
             * <p>§5.4 前置硬检查「SET 全为确定性字面量」——a+1、NOW()、子查询、函数调用
             * 一律不算（即使 NOW() 在查询白名单里，SET 场景下前镜像照样失效）。</p>
             */
            private boolean isDeterministicLiteral(SQLExpr expr) {
                return expr instanceof SQLIntegerExpr
                        || expr instanceof SQLNumberExpr
                        || expr instanceof SQLCharExpr
                        || expr instanceof SQLNCharExpr
                        || expr instanceof SQLNullExpr
                        || expr instanceof com.alibaba.druid.sql.ast.expr.SQLBooleanExpr;
            }
        };
    }

    /** R_MULTI_TABLE_DML：多表 JOIN UPDATE/DELETE → ERROR。 */
    public static Rule multiTableDml() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_MULTI_TABLE_DML);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.UPDATE && ps.getStatementType() != StatementType.DELETE) {
                    return List.of();
                }
                // UPDATE 的 JOIN 在 getTableSource()；DELETE 的 JOIN 在 getFrom()（诊断实测）
                boolean join = ps.getSourceStatement() instanceof SQLUpdateStatement u
                        && u.getTableSource() instanceof SQLJoinTableSource;
                if (!join && ps.getSourceStatement() instanceof com.alibaba.druid.sql.ast.statement.SQLDeleteStatement d
                        && d.getFrom() instanceof SQLJoinTableSource) {
                    join = true;
                }
                if (join) {
                    return List.of(hit("多表 JOIN 的 DML，前镜像无法对齐，不可回滚"));
                }
                return List.of();
            }
        };
    }

    /** R_SELECT_STAR：INSERT...SELECT 的 SELECT * → WARN。 */
    public static Rule selectStar() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_SELECT_STAR);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.INSERT
                        || !(ps.getSourceStatement() instanceof com.alibaba.druid.sql.ast.statement.SQLInsertStatement insert)) {
                    return List.of();
                }
                SQLSelect query = insert.getQuery();
                if (query == null || query.getQueryBlock() == null) {
                    return List.of();
                }
                for (SQLSelectItem item : query.getQueryBlock().getSelectList()) {
                    if (item.getExpr() instanceof com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr
                            || item.getExpr() instanceof com.alibaba.druid.sql.ast.expr.SQLPropertyExpr
                            && "*".equals(((com.alibaba.druid.sql.ast.expr.SQLPropertyExpr) item.getExpr()).getName())) {
                        return List.of(hit("INSERT...SELECT 使用 SELECT *，列结构漂移时会错位"));
                    }
                }
                return List.of();
            }
        };
    }

    /** 该组全部规则。 */
    public static List<Rule> all() {
        return List.of(noWhere(), limitOrderDml(), nondeterministicSet(), multiTableDml(), selectStar());
    }
}
