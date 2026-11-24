package com.sqlaudit.query.console;

import com.sqlaudit.common.enums.Dialect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询控制台实用语句翻译器：把 MySQL 客户端习惯的
 * {@code SHOW TABLES} / {@code DESC 表名} / {@code DESCRIBE 表名} 翻译成
 * information_schema 只读 SELECT，让两种方言在控制台里行为一致
 * （PG 没有这两条语法，用户按 MySQL 习惯输入也能得到正确结果）。
 *
 * <p><strong>安全模型不变</strong>：本类只做「已知安全形态 → 只读等价 SQL」的翻译，
 * 翻译产物仍要过 {@link com.sqlaudit.query.gate.QueryGate} 的 AST 只读判定与
 * LIMIT 注入三层保护——识别用正则但<strong>不做任何只读判定</strong>；
 * schema 以字面量嵌入（不用 {@code DATABASE()} / {@code current_schema()}，
 * 二者是刻意不入 parser 函数白名单的信息面函数），表名归一化后单引号转义，
 * 杜绝注入。命中不了白名单形态的输入原样透传，交给门禁按老规则处置。</p>
 *
 * <p>形态刻意收窄（多余子句一律不认，透传后由门禁拒绝）：
 * 不支持 {@code SHOW TABLES FROM db}（跨库请直查 information_schema）、
 * 不支持 {@code DESC t 列名}、不接受注释前缀。EXPLAIN 无需翻译——
 * parser 已把普通 EXPLAIN 解包为内部 SELECT 判只读放行，
 * {@code EXPLAIN ANALYZE} 两方言均被 EXPLAIN_ANALYZE 危险子句拒绝，
 * PG 的 {@code EXPLAIN (ANALYZE, ...)} 解析失败走 LOW_CONFIDENCE 铁律拒绝。</p>
 */
public final class ConsoleCommandRewriter {

    /** 翻译结果：sql=送入通道的 SQL（未命中=原文）；notice=结果页提示；rejectReason=无法翻译的拒绝原因。 */
    public record Rewrite(String sql, String notice, String rejectReason) {

        public static Rewrite passthrough(String sql) {
            return new Rewrite(sql, null, null);
        }

        public static Rewrite translated(String sql, String notice) {
            return new Rewrite(sql, notice, null);
        }

        public static Rewrite rejected(String reason) {
            return new Rewrite(null, null, reason);
        }

        public boolean isTranslated() {
            return notice != null;
        }
    }

    /** SHOW TABLES（仅裸形态，可带尾分号）。 */
    private static final Pattern SHOW_TABLES =
            Pattern.compile("(?i)^SHOW\\s+TABLES\\s*;?$");

    /** DESC / DESCRIBE 表名：裸标识符或反引号/双引号引用，可带尾分号。 */
    private static final Pattern DESC_TABLE =
            Pattern.compile("(?i)^(?:DESC|DESCRIBE)\\s+(`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*;?$");

    private ConsoleCommandRewriter() {
    }

    /**
     * 识别并翻译控制台实用语句。
     *
     * @param sql           用户输入（可能是任意 SQL，不匹配白名单时原样透传）
     * @param dialect       目标方言（IoTDB 无 information_schema，透传）
     * @param defaultSchema 实例默认库（连接 URL 里的那个，翻译时以字面量嵌入）
     * @return 翻译结果，见 {@link Rewrite}
     */
    public static Rewrite rewrite(String sql, Dialect dialect, String defaultSchema) {
        if (sql == null || sql.isBlank() || dialect == null || dialect == Dialect.IOTDB) {
            return Rewrite.passthrough(sql);
        }
        String trimmed = sql.strip();

        if (SHOW_TABLES.matcher(trimmed).matches()) {
            if (defaultSchema == null || defaultSchema.isBlank()) {
                return Rewrite.rejected(
                        "实例未配置默认库，无法确定 SHOW TABLES 的目标 schema；请直接查询 information_schema");
            }
            return dialect == Dialect.MYSQL
                    ? Rewrite.translated(
                            "SELECT table_name AS `table_name`, table_type AS `table_type`"
                                    + " FROM information_schema.tables"
                                    + " WHERE table_schema = " + literal(defaultSchema)
                                    + " ORDER BY table_name",
                            "SHOW TABLES 已翻译为 information_schema 只读查询（默认库 " + defaultSchema + "）")
                    : Rewrite.translated(
                            "SELECT table_name AS \"table_name\", table_type AS \"table_type\""
                                    + " FROM information_schema.tables"
                                    + " WHERE table_schema = " + literal(defaultSchema)
                                    + " ORDER BY table_name",
                            "SHOW TABLES 已翻译为 information_schema 只读查询（当前 schema "
                                    + defaultSchema + "；PG 的表清单按 schema 组织）");
        }

        Matcher desc = DESC_TABLE.matcher(trimmed);
        if (desc.matches()) {
            if (defaultSchema == null || defaultSchema.isBlank()) {
                return Rewrite.rejected(
                        "实例未配置默认库，无法确定 DESC 的目标 schema；请直接查询 information_schema");
            }
            String table = normalizeIdent(desc.group(1), dialect);
            String schemaLiteral = literal(defaultSchema);
            String tableLiteral = literal(table);
            return dialect == Dialect.MYSQL
                    ? Rewrite.translated(
                            "SELECT column_name AS `field`, column_type AS `type`,"
                                    + " is_nullable AS `nullable`, column_key AS `key`,"
                                    + " column_default AS `default`, extra AS `extra`,"
                                    + " column_comment AS `comment`"
                                    + " FROM information_schema.columns"
                                    + " WHERE table_schema = " + schemaLiteral
                                    + " AND table_name = " + tableLiteral
                                    + " ORDER BY ordinal_position",
                            "DESC 已翻译为 information_schema 只读查询（默认库 " + defaultSchema + "）")
                    : Rewrite.translated(
                            "SELECT column_name AS \"field\", data_type AS \"type\","
                                    + " is_nullable AS \"nullable\", column_default AS \"default\""
                                    + " FROM information_schema.columns"
                                    + " WHERE table_schema = " + schemaLiteral
                                    + " AND table_name = " + tableLiteral
                                    + " ORDER BY ordinal_position",
                            "DESC 已翻译为 information_schema 只读查询（当前 schema "
                                    + defaultSchema + "；字段注释请用左侧表结构面板查看）");
        }

        return Rewrite.passthrough(sql);
    }

    /** 标识符归一化：剥外围引号保持精确；PG 的裸标识符按 PG 语义折叠小写，MySQL 裸标识符保持原大小写。 */
    private static String normalizeIdent(String raw, Dialect dialect) {
        boolean quoted = raw.charAt(0) == '`' || raw.charAt(0) == '"';
        String name = quoted ? raw.substring(1, raw.length() - 1) : raw;
        if (!quoted && dialect == Dialect.PG) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    /** SQL 字符串字面量：单引号转义（表名来自用户输入，虽经形态白名单仍按不可信处理）。 */
    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
