package com.sqlaudit.parser;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.parser.iotdb.IoTDBWhitelistParser;
import com.sqlaudit.parser.mysql.MySqlDialectParser;
import com.sqlaudit.parser.postgres.PostgresDialectParser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析门面（设计文档 §4.1）：按方言路由到具体解析器。
 *
 * <p>多语句拆分（一条语句 = 一个 unit）在方言解析器内完成，
 * 返回列表与输入语句一一对应。</p>
 */
public final class SqlParserService {

    private final Map<Dialect, DialectParser> parsers = new EnumMap<>(Dialect.class);

    public SqlParserService() {
        parsers.put(Dialect.MYSQL, new MySqlDialectParser());
        parsers.put(Dialect.PG, new PostgresDialectParser());
        parsers.put(Dialect.IOTDB, new IoTDBWhitelistParser());
    }

    /**
     * 解析 SQL（含多语句拆分）。
     *
     * @param sql     原始 SQL（可能是分号分隔的多条语句）
     * @param dialect 目标方言
     * @return 每条语句一个 {@link ParsedStatement}
     */
    public List<ParsedStatement> parse(String sql, Dialect dialect) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(dialect, "dialect");
        DialectParser parser = parsers.get(dialect);
        if (parser == null) {
            throw new IllegalArgumentException("不支持的方言: " + dialect);
        }
        return parser.parse(sql);
    }
}
