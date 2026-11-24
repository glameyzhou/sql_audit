package com.sqlaudit.rule.rules;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.rule.BuiltInRules;
import com.sqlaudit.rule.Rule;
import com.sqlaudit.rule.RuleContext;
import com.sqlaudit.rule.RuleDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 其余 AST/文本规则组（README §4.3）。
 *
 * <p>R_PARSE_FAILED / R_NO_LIMIT_QUERY / R_SQL_INJECTION_PATTERN /
 * R_IOTDB_DROP / R_IOTDB_NO_TIME_RANGE / R_IOTDB_TTL / R_TX_DDL_MIX / R_BIG_UNIT_COUNT。</p>
 *
 * <p>IoTDB 语句无 Druid AST（白名单解析器），按归一化文本判定——IoTDB 的语句形态
 * 简单且白名单收窄过，文本判定的假阴性面可接受。</p>
 */
public final class AstOtherRules {

    private AstOtherRules() {
    }

    private static RuleDefinition def(RuleCode code) {
        return BuiltInRules.byCode(code);
    }

    /** R_PARSE_FAILED：解析失败 → ERROR 人工审（README §4.3 铁律的规则化）。 */
    public static Rule parseFailed() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_PARSE_FAILED);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getParseConfidence() == 0
                        && ps.getDangerousClauses().contains(DangerousClause.LOW_CONFIDENCE)) {
                    return List.of(hit("语句无法解析，转人工审核（绝不降级放行）"));
                }
                return List.of();
            }
        };
    }

    /** R_NO_LIMIT_QUERY：SELECT 无 LIMIT → ERROR（平台自动补 LIMIT 并提示）。 */
    public static Rule noLimitQuery() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_NO_LIMIT_QUERY);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getDialect() == Dialect.IOTDB) {
                    // IoTDB 白名单 parser 不产 AST、不解析 LIMIT（getLimitAst 恒 null），
                    // 无法判定是否带 LIMIT；行数上限由查询通道 setMaxRows 兜底，此处不判
                    return List.of();
                }
                if (ps.getStatementType() == StatementType.SELECT && ps.getLimitAst() == null) {
                    return List.of(hit("SELECT 未带 LIMIT，平台将自动注入默认 LIMIT"));
                }
                return List.of();
            }
        };
    }

    /**
     * R_SQL_INJECTION_PATTERN：拼接痕迹 / 注释绕过 → ERROR。
     * <p>检测用户原文中的 SQL 注释（块注释 / 行注释）。注释在 Druid 归一化输出中
     * 已剥离，因此对 originalSql 检测；正常用户极少在工单 SQL 里写注释，
     * 出现注释的常见场景是拼接生成 SQL（README §4.3 该规则的本意）。</p>
     */
    public static Rule sqlInjectionPattern() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_SQL_INJECTION_PATTERN);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                String sql = ctx.statement().getOriginalSql();
                if (sql == null) {
                    return List.of();
                }
                List<String> found = new ArrayList<>();
                if (sql.contains("/*") || sql.contains("*/")) {
                    found.add("块注释");
                }
                if (sql.contains("--")) {
                    found.add("行注释");
                }
                if (!found.isEmpty()) {
                    return List.of(hit("SQL 含注释（" + String.join("/", found) + "），疑似拼接生成，人工确认"));
                }
                return List.of();
            }
        };
    }

    /** R_IOTDB_DROP：IoTDB DROP DATABASE / TIMESERIES → ERROR，强制人工 + 元数据快照。 */
    public static Rule iotdbDrop() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_IOTDB_DROP);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getDialect() != Dialect.IOTDB || ps.getStatementType() != StatementType.DDL) {
                    return List.of();
                }
                String lower = ps.getOriginalSql().toLowerCase(Locale.ROOT).strip();
                if (lower.startsWith("drop database") || lower.startsWith("drop timeseries")
                        || lower.startsWith("drop storage group")) {
                    return List.of(hit("IoTDB DROP 操作不可自动回滚，强制人工审批 + 执行前元数据快照"));
                }
                return List.of();
            }
        };
    }

    /** R_IOTDB_NO_TIME_RANGE：IoTDB DELETE 无时间范围 → ERROR。 */
    public static Rule iotdbNoTimeRange() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_IOTDB_NO_TIME_RANGE);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getDialect() != Dialect.IOTDB || ps.getStatementType() != StatementType.DELETE) {
                    return List.of();
                }
                String lower = ps.getOriginalSql().toLowerCase(Locale.ROOT);
                if (!lower.contains("time")) {
                    return List.of(hit("IoTDB DELETE 缺少时间范围（WHERE time >= ? AND time <= ?）"));
                }
                return List.of();
            }
        };
    }

    /** R_IOTDB_TTL：TTL / 模板变更 → WARN。 */
    public static Rule iotdbTtl() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_IOTDB_TTL);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                ParsedStatement ps = ctx.statement();
                if (ps.getDialect() != Dialect.IOTDB) {
                    return List.of();
                }
                String lower = ps.getOriginalSql().toLowerCase(Locale.ROOT);
                if (lower.contains("ttl") || lower.contains("template")) {
                    return List.of(hit("IoTDB TTL / 模板变更，影响数据保留策略"));
                }
                return List.of();
            }
        };
    }

    /** R_TX_DDL_MIX：单工单混合 DDL 与 DML → WARN（工单级，挂在首个 unit 上）。 */
    public static Rule txDdlMix() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_TX_DDL_MIX);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                if (!ctx.ticketUnits().isEmpty() && ctx.ticketUnits().get(0) != ctx.statement()) {
                    return List.of(); // 工单级规则只挂一次（首个 unit）
                }
                boolean hasDdl = ctx.ticketUnits().stream()
                        .anyMatch(u -> u.getStatementType() == StatementType.DDL);
                boolean hasDml = ctx.ticketUnits().stream().anyMatch(u ->
                        u.getStatementType() == StatementType.UPDATE
                                || u.getStatementType() == StatementType.INSERT
                                || u.getStatementType() == StatementType.DELETE);
                if (hasDdl && hasDml) {
                    return List.of(hit("单工单混合 DDL 与 DML（工单不是事务，建议拆分）"));
                }
                return List.of();
            }
        };
    }

    /** R_BIG_UNIT_COUNT：单工单 unit 数超阈值（默认 200）→ WARN（工单级）。 */
    public static Rule bigUnitCount() {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return def(RuleCode.R_BIG_UNIT_COUNT);
            }

            @Override
            public List<com.sqlaudit.common.model.RuleHit> check(RuleContext ctx) {
                if (!ctx.ticketUnits().isEmpty() && ctx.ticketUnits().get(0) != ctx.statement()) {
                    return List.of();
                }
                long threshold = definition().longParam("threshold", 200);
                if (ctx.ticketUnits().size() > threshold) {
                    return List.of(hit("单工单 unit 数 " + ctx.ticketUnits().size() + " 超阈值 " + threshold));
                }
                return List.of();
            }
        };
    }

    /** 该组全部规则。 */
    public static List<Rule> all() {
        return List.of(parseFailed(), noLimitQuery(), sqlInjectionPattern(),
                iotdbDrop(), iotdbNoTimeRange(), iotdbTtl(), txDdlMix(), bigUnitCount());
    }
}
