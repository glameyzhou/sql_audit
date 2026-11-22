package com.sqlaudit.worker.rollback;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.executor.engine.UnitExecutionEngine;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.lifecycle.RollbackLifecycleService;
import com.sqlaudit.rollback.reverse.ReverseSqlGenerator;
import com.sqlaudit.rollback.reverse.ReverseStatement;
import com.sqlaudit.storage.model.TicketUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向 SQL 落库桥（§5.3 步骤 4：执行成功后用前镜像生成反向语句并持久化）。
 *
 * <p>生成规则按原语句类型分流（ReverseSqlGenerator）：</p>
 * <ul>
 *   <li>UPDATE：前镜像行 → 每行一条反向 UPDATE；</li>
 *   <li>DELETE：前镜像行 → 每行一条反向 INSERT；</li>
 *   <li>INSERT：取到的主键值 → 一条 DELETE WHERE pk=?。</li>
 * </ul>
 *
 * <p>落库失败 → 引擎标 needs_confirm（保回滚能力优先，人工兜底补录）。</p>
 */
public class RollbackPersisterImpl implements UnitExecutionEngine.RollbackPersister {

    private static final Logger log = LoggerFactory.getLogger(RollbackPersisterImpl.class);

    /** 回滚有效期（§5.9：默认 24h）。 */
    private static final Duration ROLLBACK_TTL = Duration.ofHours(24);

    private final RollbackLifecycleService lifecycle;
    private final ReverseSqlGenerator generator = new ReverseSqlGenerator();
    private final SqlParserService parser;

    public RollbackPersisterImpl(RollbackLifecycleService lifecycle, SqlParserService parser) {
        this.lifecycle = lifecycle;
        this.parser = parser;
    }

    @Override
    public boolean persist(TicketUnit unit, ProductionGateway.ExecutionResult result) {
        if (result.outcome() != ProductionGateway.Outcome.SUCCESS) {
            return false; // 引擎只对 SUCCESS 调用本方法，防御性兜底
        }
        try {
            List<ReverseStatement> statements = buildReverse(unit, result);
            List<String> preimageRows = toCanonicalRows(result.preimageRows());
            lifecycle.persistAndVerify(
                    unit.getId(),
                    "MIRROR",
                    "FULL",
                    preimageRows,
                    statements,
                    dialectOf(unit),
                    LocalDateTime.now(),
                    ROLLBACK_TTL);
            return true;
        } catch (Exception e) {
            // 任何落库异常都让引擎转 needs_confirm（不吞、不半写）；根因留日志供人工补录时排查
            log.error("[rollback-persist] unit={} 反向 SQL 落库失败，转 needs_confirm 人工兜底", unit.getId(), e);
            return false;
        }
    }

    /**
     * 前镜像行集归一：列名转小写（H2 等库元数据返回大写列名；反向 SQL 的列名来自
     * AST 是小写，不归一则 row.get(col) 全为 null）、值含 null 的行转可变 List
     * （ReverseStatement 的 List.copyOf 不容 null——参数化值本身允许 null）。
     */
    private static List<Map<String, Object>> normalizePreimage(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> lower = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                lower.put(e.getKey() == null ? null : e.getKey().toLowerCase(), e.getValue());
            }
            normalized.add(lower);
        }
        return normalized;
    }

    private List<ReverseStatement> buildReverse(TicketUnit unit, ProductionGateway.ExecutionResult result) {
        String table = tableName(unit);
        String sql = new String(unit.getStatement(), StandardCharsets.UTF_8);
        var parsed = parser.parse(sql, dialectOf(unit));
        if (parsed.isEmpty()) {
            throw new IllegalStateException("重解析失败: " + sql);
        }
        var ps = parsed.get(0);
        return switch (ps.getStatementType()) {
            case UPDATE -> {
                List<Map<String, Object>> rows = normalizePreimage(result.preimageRows());
                List<String> pk = pkOf(result, rows);
                List<String> setCols = setColumnsOf(ps, pk);
                yield generator.forUpdate(table, columnsOf(rows, pk, setCols), rows, setCols, pk);
            }
            case DELETE -> {
                List<Map<String, Object>> rows = normalizePreimage(result.preimageRows());
                yield generator.forDelete(table, columnsOf(rows, List.of(), List.of()), rows);
            }
            case INSERT -> generator.forInsert(table, pkOf(result, null), result.insertedPkValues());
            default -> throw new IllegalStateException(
                    "不支持的回滚类型: " + ps.getStatementType());
        };
    }

    /** 前镜像行集 → 依赖检测用规范行（列序无关拼接，与 preimageHash 口径一致）。 */
    private static List<String> toCanonicalRows(List<Map<String, Object>> preimageRows) {
        if (preimageRows == null) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> row : preimageRows) {
            // LinkedHashMap 按 key 排序保证列序无关
            Map<String, Object> sorted = new java.util.TreeMap<>(row);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("|");
                }
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    /** 从前镜像行推断列清单（首行的 key 序；列序由 gateway 的 preimageColumns 保证）。 */
    private static List<String> columnsOf(List<Map<String, Object>> rows, List<String> pk,
                                          List<String> setCols) {
        if (rows != null && !rows.isEmpty()) {
            return new ArrayList<>(rows.get(0).keySet());
        }
        List<String> cols = new ArrayList<>(pk);
        for (String c : setCols) {
            if (!cols.contains(c)) {
                cols.add(c);
            }
        }
        return cols;
    }

    private static List<String> pkOf(ProductionGateway.ExecutionResult result,
                                     List<Map<String, Object>> normalizedRows) {
        // 前镜像首列即主键（PreimagePlanner 列序口径：主键在前）
        if (normalizedRows != null && !normalizedRows.isEmpty()) {
            var first = normalizedRows.get(0);
            if (!first.isEmpty()) {
                return List.of(first.keySet().iterator().next());
            }
        }
        if (result.insertedPkValues() != null && !result.insertedPkValues().isEmpty()) {
            return List.of("id"); // INSERT 路径：主键列名以 id 占位（W2 由 request 透传）
        }
        return List.of("id");
    }

    private static List<String> setColumnsOf(ParsedStatement ps, List<String> pk) {
        if (ps.getSourceStatement() instanceof com.alibaba.druid.sql.ast.statement.SQLUpdateStatement update) {
            List<String> cols = new ArrayList<>();
            for (var item : update.getItems()) {
                String col = item.getColumn() instanceof com.alibaba.druid.sql.ast.SQLName n
                        ? n.getSimpleName() : String.valueOf(item.getColumn());
                if (!cols.contains(col)) {
                    cols.add(col);
                }
            }
            return cols;
        }
        return new ArrayList<>();
    }

    private static String tableName(TicketUnit unit) {
        return unit.getObjectName() != null ? unit.getObjectName() : "t";
    }

    private static List<Map<String, Object>> orEmpty(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static Dialect dialectOf(TicketUnit unit) {
        String hint = unit.getDialectSpecific();
        if (hint != null) {
            try {
                return Dialect.valueOf(hint.toUpperCase());
            } catch (IllegalArgumentException ignore) {
                // 默认 MYSQL
            }
        }
        return Dialect.MYSQL;
    }
}
