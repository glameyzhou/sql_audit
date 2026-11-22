package com.sqlaudit.parser.iotdb;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.SqlObject;
import com.sqlaudit.parser.DialectParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Apache IoTDB 1.x 语句白名单解析器（设计文档 §4.1 / §5.7）。
 *
 * <p><strong>MVP 不做 AST</strong>（不自研 ANTLR grammar，P2 复用官方
 * iotdb-core/antlr 生成的解析器）：按语句类型白名单放行，
 * 白名单外（TTL / 模板 / LOAD / FLUSH / GRANT 等）一律 confidence=0 高危。</p>
 *
 * <p>白名单语句类型：SELECT / INSERT / DELETE / CREATE / DROP / SHOW。
 * 其中 DELETE 是否带时间范围属规则层（R_IOTDB_NO_TIME_RANGE），本层只判类型。</p>
 */
public class IoTDBWhitelistParser implements DialectParser {

    @Override
    public List<ParsedStatement> parse(String sql) {
        Objects.requireNonNull(sql, "sql");
        List<ParsedStatement> result = new ArrayList<>();
        for (String raw : sql.split(";")) {
            String stmt = raw.strip();
            if (stmt.isEmpty()) {
                continue;
            }
            result.add(normalize(stmt));
        }
        if (result.isEmpty()) {
            result.add(failed(sql));
        }
        return result;
    }

    private ParsedStatement normalize(String stmt) {
        String lower = stmt.toLowerCase(Locale.ROOT);
        String firstWord = firstWord(lower);

        ParsedStatement ps = new ParsedStatement();
        ps.setDialect(Dialect.IOTDB);
        ps.setOriginalSql(stmt);
        ps.setNormalizedSql(stmt);
        ps.setParseConfidence(100);

        switch (firstWord) {
            case "select" -> {
                ps.setStatementType(StatementType.SELECT);
                ps.setReadOnly(true);
                ps.setObjects(List.of(objectAfter(stmt, "from")));
            }
            case "insert" -> {
                ps.setStatementType(StatementType.INSERT);
                ps.setReadOnly(false);
                ps.setObjects(List.of(objectAfter(stmt, "into")));
            }
            case "delete" -> {
                ps.setStatementType(StatementType.DELETE);
                ps.setReadOnly(false);
                ps.setObjects(List.of(objectAfter(stmt, "from")));
            }
            case "create", "drop" -> {
                ps.setStatementType(StatementType.DDL);
                ps.setReadOnly(false);
            }
            case "show" -> {
                ps.setStatementType(StatementType.OTHER);
                ps.setReadOnly(false); // 与 MySQL 同口径：只读白名单只认 SELECT
            }
            default -> {
                // 白名单外：TTL / 模板 / LOAD / FLUSH / SET TTL / GRANT / MERGE 等
                ps.setStatementType(StatementType.OTHER);
                ps.setReadOnly(false);
                ps.setParseConfidence(0);
                ps.getDangerousClauses().add(
                        com.sqlaudit.common.enums.DangerousClause.LOW_CONFIDENCE);
            }
        }
        return ps;
    }

    private ParsedStatement failed(String sql) {
        ParsedStatement ps = new ParsedStatement();
        ps.setDialect(Dialect.IOTDB);
        ps.setStatementType(StatementType.OTHER);
        ps.setOriginalSql(sql);
        ps.setNormalizedSql(sql);
        ps.setParseConfidence(0);
        ps.setReadOnly(false);
        ps.getDangerousClauses().add(com.sqlaudit.common.enums.DangerousClause.LOW_CONFIDENCE);
        return ps;
    }

    private static String firstWord(String lower) {
        int i = 0;
        while (i < lower.length() && Character.isWhitespace(lower.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < lower.length() && Character.isLetter(lower.charAt(i))) {
            i++;
        }
        return lower.substring(start, i);
    }

    /** 提取关键字后的对象路径（时间序列路径 / storage group）。 */
    private static SqlObject objectAfter(String stmt, String keyword) {
        String lower = stmt.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(keyword);
        if (idx < 0) {
            return new SqlObject(null, null, "TIMESERIES");
        }
        String rest = stmt.substring(idx + keyword.length()).stripLeading();
        int end = 0;
        while (end < rest.length()
                && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '.'
                || rest.charAt(end) == '_' || rest.charAt(end) == '-' || rest.charAt(end) == '`')) {
            end++;
        }
        String path = rest.substring(0, end).replace("`", "");
        return new SqlObject(null, path.isEmpty() ? null : path, "TIMESERIES");
    }
}
