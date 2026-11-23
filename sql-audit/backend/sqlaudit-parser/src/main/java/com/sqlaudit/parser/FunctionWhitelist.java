package com.sqlaudit.parser;

import java.util.Set;

/**
 * 内置纯函数白名单（README §4.3「调用存储函数 / 存储过程 / 触发器副作用」行）。
 *
 * <p>解析层无法区分内置函数与用户自定义/存储函数（SQLMethodInvokeExpr 形态相同），
 * 因此采用<strong>白名单策略</strong>：只有已知确定性的内置纯函数视为只读安全，
 * 其余任何函数调用一律标 {@code FUNCTION_SIDE_EFFECT}，强制非只读。</p>
 *
 * <p>这符合只读判定的总原则：<strong>假阴性率必须为 0，宁可误判为写操作</strong>。</p>
 *
 * <p>刻意<strong>不</strong>入白名单的常见函数（非确定性或有副作用/信息泄露面）：</p>
 * <ul>
 *   <li>SLEEP / BENCHMARK / GET_LOCK / RELEASE_LOCK / LOAD_FILE —— 明确副作用；</li>
 *   <li>RAND / UUID / SYSDATE —— 非确定性（SYSDATE 每行求值，NOW() 语句级恒定故入白）；</li>
 *   <li>USER / CURRENT_USER / DATABASE / SCHEMA / VERSION / CONNECTION_ID —— 信息面函数，
 *       查询通道场景刻意保守（误判 → 走工单，可接受）。</li>
 * </ul>
 */
public final class FunctionWhitelist {

    private FunctionWhitelist() {
    }

    /** 确定性内置函数（小写）。新增必须评审：非确定性/有副作用的函数绝不入内。 */
    public static final Set<String> PURE_FUNCTIONS = Set.of(
            // 日期时间（NOW/CURRENT_TIMESTAMP 语句级恒定）
            "now", "current_timestamp", "current_date", "current_time", "current_time()",
            "localtime", "localtimestamp",
            "date", "time", "timestamp", "year", "month", "day", "hour", "minute", "second",
            "dayofweek", "dayofmonth", "dayofyear", "weekday", "week", "weekofyear", "quarter",
            "date_format", "date_add", "date_sub", "adddate", "subdate",
            "datediff", "timestampdiff", "timestampadd", "period_add", "period_diff",
            "str_to_date", "maketime", "makedate", "sec_to_time", "time_to_sec",
            "extract", "last_day", "to_days", "from_days", "unix_timestamp", "from_unixtime",
            // 字符串
            "concat", "concat_ws", "substring", "substr", "left", "right", "mid",
            "upper", "ucase", "lower", "lcase", "length", "char_length", "character_length",
            "octet_length", "trim", "ltrim", "rtrim", "lpad", "rpad", "repeat", "reverse",
            "replace", "substring_index", "instr", "locate", "position", "ascii", "char",
            "hex", "unhex", "bin", "oct", "conv", "elt", "field", "find_in_set",
            "insert", "space", "strcmp", "quote", "format", "ord",
            // 数值
            "abs", "sign", "ceil", "ceiling", "floor", "round", "truncate", "mod",
            "greatest", "least", "div", "pow", "power", "sqrt", "exp", "ln", "log", "log2", "log10",
            "pi", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "cot", "degrees", "radians",
            "rand_" /* 占位防误加，无实义 */,
            // 类型转换与判空
            "cast", "convert", "if", "ifnull", "nullif", "isnull", "coalesce", "nvl",
            // 聚合与窗口
            "count", "sum", "avg", "min", "max", "group_concat", "std", "stddev", "stddev_pop",
            "stddev_samp", "var_pop", "var_samp", "variance", "bit_and", "bit_or", "bit_xor",
            "json_arrayagg", "json_objectagg",
            "row_number", "rank", "dense_rank", "ntile", "lag", "lead",
            "first_value", "last_value", "nth_value", "cume_dist", "percent_rank",
            // JSON（纯读取）
            "json_extract", "json_unquote", "json_valid", "json_type", "json_length",
            "json_depth", "json_contains", "json_contains_path", "json_keys", "json_search",
            "json_array", "json_object", "json_quote", "json_array_append", "json_array_insert",
            "json_insert", "json_replace", "json_set", "json_remove", "json_merge", "json_merge_patch",
            "json_pretty", "json_storage_size", "json_table",
            // 位运算与加解密确定性函数（纯计算）
            "bit_count", "bit_length", "md5", "sha", "sha1", "sha2", "crc32",
            "aes_encrypt", "aes_decrypt", "to_base64", "from_base64", "compress", "uncompress",
            "uncompressed_length", "encode", "decode", "des_encrypt", "des_decrypt",
            // 其他
            "inet_aton", "inet_ntoa", "inet6_aton", "inet6_ntoa", "is_ipv4", "is_ipv6",
            "is_ipv4_compat", "is_ipv4_mapped", "any_value", "default", "values", "interval",
            "bitmap_count", "coalesce_olap"
    );

    /** 是否为白名单内的纯函数（方法名不区分大小写）。 */
    public static boolean isPure(String methodName) {
        if (methodName == null) {
            return false;
        }
        String name = methodName.toLowerCase();
        if ("rand_".equals(name)) {
            return false; // 占位项永远不算
        }
        return PURE_FUNCTIONS.contains(name);
    }
}
