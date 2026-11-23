package com.sqlaudit.rule.rules;

import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.rule.BuiltInRules;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleContext;
import com.sqlaudit.rule.RuleDefinition;
import com.sqlaudit.rule.TableMetadataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 需要表元数据的规则组（README §4.3）。
 *
 * <p>R_NO_PK / R_OBJECT_NOT_ALLOWED / R_SENSITIVE_COLUMN / R_FULL_TABLE_SCAN。</p>
 *
 * <p>元数据不可用时（isAvailable()=false）：R_NO_PK 按「无法证明可回滚」保守阻断；
 * 其余（白名单/掩码）降为不判——它们是权限类规则，工单入库前的其他层会拦。</p>
 */
public final class MetadataRules {

    private MetadataRules() {
    }

    private static RuleDefinition def(RuleCode code) {
        return BuiltInRules.byCode(code);
    }

    /** R_NO_PK：目标表无主键（DML）→ ERROR（影响回滚能力）。 */
    public static Rule noPk() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_NO_PK);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getStatementType() != StatementType.UPDATE
                        && ps.getStatementType() != StatementType.DELETE
                        && ps.getStatementType() != StatementType.INSERT) {
                    return List.of();
                }
                TableMetadataProvider meta = ctx.metadata();
                if (meta == null || !meta.isAvailable()) {
                    // 元数据不可达：DML 的回滚能力无法证明 → 保守阻断（无法证明安全即不安全）
                    return List.of(hit("表元数据不可用，无法校验主键（回滚能力无法证明，保守阻断）"));
                }
                List<com.sqlaudit.common.model.RuleHit> hits = new ArrayList<>();
                ps.getObjects().forEach(obj -> {
                    if (obj.getName() == null) {
                        return;
                    }
                    TableMetadataProvider.TableMetadata tm = meta.getTable(obj.getSchema(), obj.getName());
                    if (tm != null && !tm.hasPrimaryKey()) {
                        hits.add(hit("目标表无主键，无法生成反向 DML: " + obj));
                    }
                });
                return hits;
            }
        };
    }

    /** R_OBJECT_NOT_ALLOWED：目标对象不在实例白名单 → ERROR。 */
    public static Rule objectNotAllowed() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_OBJECT_NOT_ALLOWED);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                TableMetadataProvider meta = ctx.metadata();
                if (meta == null || !meta.isAvailable()) {
                    return List.of(); // 权限类规则元数据缺失时放行给后续层（见类 javadoc）
                }
                // CREATE 族语句的对象此刻本就不存在（白名单管的是既有对象的读写），
                // 跳过——新建对象由表名规范 / 审批链把关，执行期还有漂移门禁
                Object src = ctx.statement().getSourceStatement();
                if (src instanceof com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement
                        || src instanceof com.alibaba.druid.sql.ast.statement.SQLCreateIndexStatement
                        || src instanceof com.alibaba.druid.sql.ast.statement.SQLCreateDatabaseStatement
                        || src instanceof com.alibaba.druid.sql.ast.statement.SQLCreateViewStatement) {
                    return List.of();
                }
                List<com.sqlaudit.common.model.RuleHit> hits = new ArrayList<>();
                ctx.statement().getObjects().forEach(obj -> {
                    if (obj.getName() == null) {
                        return;
                    }
                    TableMetadataProvider.TableMetadata tm = meta.getTable(obj.getSchema(), obj.getName());
                    if (tm == null) {
                        // 无法解析或不存在：未限定库名且实例无默认库、库名写错、凭据/连接失败等
                        // 都会走到这里——与「表存在但不在白名单」是两种完全不同的情况
                        hits.add(hit("目标对象无法解析或不存在（请确认库名前缀或实例默认库）: " + obj));
                    } else if (!tm.whitelisted()) {
                        hits.add(hit("目标对象不在实例白名单: " + obj));
                    }
                });
                return hits;
            }
        };
    }

    /** R_SENSITIVE_COLUMN：命中掩码列且未授权 → ERROR（本层只报「涉及掩码列」，授权判定在 server 层）。 */
    public static Rule sensitiveColumn() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_SENSITIVE_COLUMN);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                TableMetadataProvider meta = ctx.metadata();
                if (meta == null || !meta.isAvailable()) {
                    return List.of();
                }
                List<com.sqlaudit.common.model.RuleHit> hits = new ArrayList<>();
                ctx.statement().getObjects().forEach(obj -> {
                    if (obj.getName() == null) {
                        return;
                    }
                    TableMetadataProvider.TableMetadata tm = meta.getTable(obj.getSchema(), obj.getName());
                    if (tm != null && !tm.sensitiveColumns().isEmpty()) {
                        hits.add(hit("目标表含掩码列（" + tm.sensitiveColumns() + "），查询/导出需授权"));
                    }
                });
                return hits;
            }
        };
    }

    /** 该组全部规则（R_FULL_TABLE_SCAN 需索引元数据 + EXPLAIN，W2 集成时补）。 */
    public static List<Rule> all() {
        return List.of(noPk(), objectNotAllowed(), sensitiveColumn());
    }
}
