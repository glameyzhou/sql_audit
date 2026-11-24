package com.sqlaudit.query.gate;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.SqlObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 实例默认库边界校验（查询台与工单共用的「平台配置哪个库就只动哪个库」硬边界）。
 *
 * <p>平台的产品语义：用户只看到「实例」，库是平台配置时定死的
 * （{@code Instance.defaultSchema}）。因此语句里出现限定名 {@code 其他库.表} 属于
 * 越界——查询台拒绝、工单提交预审报 {@code SCHEMA_OUT_OF_BOUND}。</p>
 *
 * <p><strong>只对 MySQL 生效，PG 一律不校验</strong>：PG 的 {@code defaultSchema} 语义是
 * JDBC URL 里的 database 名（PG 建连必须有库名），而语句里的 schema 前缀（{@code public.x}）
 * 是<strong>同一库内的命名空间</strong>，合法且无法跨库。若对 PG 做 schema 校验会误杀
 * 所有带 schema 前缀的正常引用。PG 的库级隔离由 URL 钉库天然保证（PG 本身不支持跨库 SQL）。
 * IoTDB 无库/schema 概念，也不校验。</p>
 *
 * <p>判定依据是 parser 收集的 {@link ParsedStatement#getObjects()}（{@code SqlObject.schema}）——
 * 已剥引号、CTE 别名已排除，无需再正则。</p>
 */
public final class SchemaBoundaryChecker {

    private SchemaBoundaryChecker() {
    }

    /**
     * 校验对象清单的库边界。
     *
     * @param dialect        目标方言（PG / IoTDB 恒空）
     * @param expectedSchema 实例默认库；null/blank 表示无边界锚点（实例未配置默认库），
     *                       此时不做限制——限定名是用户唯一能引用库的方式
     * @param objects        语句的对象引用（可为 null，按空处理）
     * @return 违规描述列表（空 = 合规）
     */
    public static List<String> violations(Dialect dialect, String expectedSchema, List<SqlObject> objects) {
        List<String> out = new ArrayList<>();
        if (dialect != Dialect.MYSQL || objects == null) {
            return out;
        }
        if (expectedSchema == null || expectedSchema.isBlank()) {
            return out; // 无默认库锚点 → 不限制
        }
        for (SqlObject obj : objects) {
            if (obj == null || obj.getSchema() == null || obj.getSchema().isBlank()) {
                continue; // 裸表名落在默认库，合法
            }
            if (!expectedSchema.equalsIgnoreCase(obj.getSchema())) {
                out.add("实例边界：仅允许操作实例配置的默认库「" + expectedSchema
                        + "」，语句引用了其他库「" + obj.getSchema() + "." + obj.getName() + "」");
            }
        }
        return out;
    }

    /** 便捷重载：单条语句。 */
    public static List<String> violations(Dialect dialect, String expectedSchema, ParsedStatement ps) {
        if (ps == null) {
            return List.of();
        }
        return violations(dialect, expectedSchema, ps.getObjects());
    }
}
