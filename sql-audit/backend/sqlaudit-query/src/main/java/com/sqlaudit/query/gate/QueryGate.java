package com.sqlaudit.query.gate;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.SqlParserService;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 查询门禁（设计文档 §6.2 查询链路第一跳）。
 *
 * <p>只读判定完全复用 A-1 parser 契约（{@link SqlParserService} +
 * {@link ParsedStatement#isReadOnly()}），本类不重新实现任何解析/判定逻辑。
 * 规则：</p>
 * <ul>
 *   <li>多条语句 → 拒绝（查询通道单语句）；</li>
 *   <li>非只读（isReadOnly=false 或命中 dangerousClauses）→ 拒绝并给出原因，
 *       用户可降级走审核工单（§6.2 流程图右侧分支）；</li>
 *   <li>解析失败（LOW_CONFIDENCE）→ parser 已判 readOnly=false，同上拒绝。</li>
 * </ul>
 */
public final class QueryGate {

    private final SqlParserService parser;

    public QueryGate() {
        this(new SqlParserService());
    }

    public QueryGate(SqlParserService parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * 门禁检查。
     *
     * @param sql     用户 SQL（可能是分号分隔的多条语句）
     * @param dialect 目标方言
     * @return 判定结果，见 {@link GateResult}
     */
    public GateResult check(String sql, Dialect dialect) {
        java.util.List<ParsedStatement> statements = parser.parse(sql, dialect);

        if (statements.size() != 1) {
            return GateResult.reject("查询通道仅允许单条语句，实际解析出 " + statements.size() + " 条");
        }

        ParsedStatement ps = statements.get(0);
        if (!ps.isReadOnly() || !ps.getDangerousClauses().isEmpty()) {
            String clauses = ps.getDangerousClauses().stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            String type = ps.getStatementType() == null
                    ? StatementType.OTHER.name() : ps.getStatementType().name();
            String reason = clauses.isEmpty()
                    ? "非只读语句（" + type + "），请走审核工单"
                    : "非只读语句（" + type + "），命中危险子句：" + clauses;
            return GateResult.reject(reason);
        }

        // LIMIT 注入层可用性：仅 Druid 方言（MySQL/PG）产 AST；IoTDB 白名单 → 兜底层
        boolean injectable = dialect != Dialect.IOTDB;
        return new GateResult(true, null, ps, injectable);
    }
}
