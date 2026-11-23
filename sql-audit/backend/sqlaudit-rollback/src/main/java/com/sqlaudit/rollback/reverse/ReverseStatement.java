package com.sqlaudit.rollback.reverse;

import java.util.List;

/**
 * 一条参数化反向语句（README §4.4 存储模型）。
 *
 * <p>值<strong>参数化存储</strong>（{@code ?} 占位符 + params），展示时才拼字面量
 * （README §4.4 值序列化要防注入）；落库到 rollback_statement 子表（reverse_sql +
 * value_params 分列，不是单行 VARBINARY——v2 的错误已修正）。</p>
 *
 * @param sql    带 ? 占位符的参数化语句
 * @param params 该语句的参数（顺序与 ? 对齐；旧值）
 */
public record ReverseStatement(String sql, List<Object> params) {

    public ReverseStatement {
        // 参数值允许 null（旧值本身可为 NULL），copyOf 不容 null → 用 ArrayList 包一层
        params = params == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(params));
    }

    /** 展示用 SQL（拼字面量，仅日志/预览；执行必须走参数化）。 */
    public String displaySql() {
        StringBuilder sb = new StringBuilder();
        int paramIdx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && paramIdx < params.size()) {
                sb.append(literalOf(params.get(paramIdx++)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String literalOf(Object p) {
        if (p == null) {
            return "NULL";
        }
        if (p instanceof Number || p instanceof Boolean) {
            return String.valueOf(p);
        }
        return "'" + String.valueOf(p).replace("'", "''") + "'";
    }
}
