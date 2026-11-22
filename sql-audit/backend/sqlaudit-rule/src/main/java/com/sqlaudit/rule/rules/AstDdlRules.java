package com.sqlaudit.rule.rules;

import com.alibaba.druid.sql.ast.statement.SQLAlterTableDropColumnItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateIndexStatement;
import com.alibaba.druid.sql.ast.statement.SQLDropTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTruncateStatement;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.rule.BuiltInRules;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 的 AST 规则组（设计文档 §4.3 / §5.6）。
 *
 * <p>R_DDL_DESTRUCTIVE / R_DDL_NO_IF_EXISTS / R_TABLE_NAME / R_INDEX_NAME。</p>
 *
 * <p>破坏性 DDL 按列级/整表级都标 R_DDL_DESTRUCTIVE（§5.6 的影子表/列投影分流
 * 由 rollback 模块进一步判定，规则层只负责阻断与路由）。</p>
 */
public final class AstDdlRules {

    private AstDdlRules() {
    }

    private static RuleDefinition def(String code) {
        return BuiltInRules.byCode(code);
    }

    /** R_DDL_DESTRUCTIVE：DROP TABLE / TRUNCATE / DROP COLUMN / MODIFY COLUMN → ERROR（影子表流程）。 */
    public static Rule ddlDestructive() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_DDL_DESTRUCTIVE");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.DDL) {
                    return List.of();
                }
                Object src = ps.getSourceStatement();
                if (src instanceof SQLDropTableStatement || src instanceof SQLTruncateStatement) {
                    return List.of(hit("破坏性 DDL（整表级），走影子表流程", java.util.Map.of("level", "TABLE")));
                }
                if (src instanceof SQLAlterTableStatement alter) {
                    for (SQLAlterTableItem item : alter.getItems()) {
                        if (item instanceof SQLAlterTableDropColumnItem) {
                            return List.of(hit("破坏性 DDL（DROP COLUMN，列级），走列投影备份",
                                    java.util.Map.of("level", "COLUMN")));
                        }
                        String kind = item.getClass().getSimpleName();
                        if (kind.contains("ModifyColumn") || kind.contains("ChangeColumn")) {
                            return List.of(hit("破坏性 DDL（MODIFY/CHANGE COLUMN，可能截断数据），"
                                    + "执行前需越界行数检查", java.util.Map.of("level", "COLUMN")));
                        }
                    }
                }
                return List.of();
            }
        };
    }

    /** R_DDL_NO_IF_EXISTS：危险 DDL 未带幂等保护 → WARN（不自动改写用户 SQL）。 */
    public static Rule ddlNoIfExists() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_DDL_NO_IF_EXISTS");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.DDL) {
                    return List.of();
                }
                if (ps.getSourceStatement() instanceof SQLDropTableStatement drop && !drop.isIfExists()) {
                    return List.of(hit("DROP TABLE 未带 IF EXISTS（平台不自动改写，幂等靠 unit 状态机）"));
                }
                return List.of();
            }
        };
    }

    /** R_TABLE_NAME：表命名规范 → WARN。 */
    public static Rule tableName() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_TABLE_NAME");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.DDL) {
                    return List.of();
                }
                String pattern = definition().longParam("pattern", 0) == 0
                        ? "^[a-z][a-z0-9_]{0,63}$" : (String) definition().params().get("pattern");
                List<com.sqlaudit.common.model.RuleHit> hits = new ArrayList<>();
                ps.getObjects().forEach(obj -> {
                    String name = obj.getName();
                    if (name != null && !name.matches(pattern)) {
                        hits.add(hit("表名不符合命名规范 " + pattern + ": " + name));
                    }
                });
                return hits;
            }
        };
    }

    /** R_INDEX_NAME：索引命名规范 → WARN。 */
    public static Rule indexName() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def("R_INDEX_NAME");
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(com.sqlaudit.rule.RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.DDL
                        || !(ps.getSourceStatement() instanceof SQLCreateIndexStatement create)) {
                    return List.of();
                }
                String indexName = create.getName() != null
                        ? create.getName().getSimpleName() : null;
                if (indexName != null && !indexName.startsWith("idx_")) {
                    return List.of(hit("索引名不符合 idx_ 前缀规范: " + indexName));
                }
                return List.of();
            }
        };
    }

    /** 该组全部规则。 */
    public static List<Rule> all() {
        return List.of(ddlDestructive(), ddlNoIfExists(), tableName(), indexName());
    }
}
