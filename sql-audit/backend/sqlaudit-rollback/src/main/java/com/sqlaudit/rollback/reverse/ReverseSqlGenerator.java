package com.sqlaudit.rollback.reverse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 反向 SQL 生成器（设计文档 §5.4 / §5.5）——用执行事务内采集的前镜像逐行生成反向语句。
 *
 * <p>生成规则（参数化，值绝不拼进 SQL 文本）：</p>
 * <table>
 *   <tr><th>原语句</th><th>反向</th></tr>
 *   <tr><td>UPDATE t SET a,b WHERE cond</td><td>每行：{@code UPDATE t SET a=?,b=? WHERE pk=?}</td></tr>
 *   <tr><td>DELETE FROM t WHERE cond</td><td>每行：{@code INSERT INTO t (cols...) VALUES (?,...)}</td></tr>
 *   <tr><td>INSERT INTO t ... (主键已知)</td><td>{@code DELETE FROM t WHERE pk=?}</td></tr>
 * </table>
 *
 * <p>注意：前镜像行集与 affected_rows 的对账（§5.3 硬门禁）在 executor 层做；
 * 本类假设输入行集就是对账通过的。</p>
 */
public class ReverseSqlGenerator {

    /**
     * UPDATE 的反向语句（每前镜像行一条）。
     *
     * @param preimageColumns 前镜像列序（主键在前 + SET 列，见 PreimagePlanner）
     * @param preimageRows    前镜像行（Map&lt;列名, 旧值&gt;）
     * @param tableName       目标表（含 schema 前缀时原样使用）
     * @param pkColumns       主键列（支持复合主键）
     */
    public List<ReverseStatement> forUpdate(String tableName, List<String> preimageColumns,
                                            List<Map<String, Object>> preimageRows,
                                            List<String> setColumns, List<String> pkColumns) {
        List<ReverseStatement> result = new ArrayList<>(preimageRows.size());
        String setClause = String.join(", ", setColumns.stream().map(c -> c + " = ?").toList());
        String whereClause = String.join(" AND ", pkColumns.stream().map(c -> c + " = ?").toList());

        for (Map<String, Object> row : preimageRows) {
            List<Object> params = new ArrayList<>(setColumns.size() + pkColumns.size());
            for (String col : setColumns) {
                params.add(row.get(col));
            }
            for (String col : pkColumns) {
                params.add(row.get(col));
            }
            result.add(new ReverseStatement(
                    "UPDATE " + tableName + " SET " + setClause + " WHERE " + whereClause, params));
        }
        return result;
    }

    /**
     * DELETE 的反向语句（每前镜像行一条反向 INSERT）。
     *
     * @param preimageColumns 前镜像列序（= 反向 INSERT 的列清单，已排除生成列）
     */
    public List<ReverseStatement> forDelete(String tableName, List<String> preimageColumns,
                                            List<Map<String, Object>> preimageRows) {
        List<ReverseStatement> result = new ArrayList<>(preimageRows.size());
        String columnList = String.join(", ", preimageColumns);
        String placeholders = String.join(", ", preimageColumns.stream().map(c -> "?").toList());

        for (Map<String, Object> row : preimageRows) {
            List<Object> params = new ArrayList<>(preimageColumns.size());
            for (String col : preimageColumns) {
                params.add(row.get(col));
            }
            result.add(new ReverseStatement(
                    "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")",
                    params));
        }
        return result;
    }

    /**
     * INSERT 的反向语句（DELETE WHERE pk，一条）。
     *
     * <p>主键来源（§5.4 / §5.5）：显式主键直接用；MySQL 自增取
     * {@code LAST_INSERT_ID()}（同连接、单条）；PG 取 {@code RETURNING pk}。</p>
     *
     * @param pkValues 主键值（支持复合主键）
     */
    public List<ReverseStatement> forInsert(String tableName, List<String> pkColumns,
                                            List<Object> pkValues) {
        if (pkColumns.size() != pkValues.size()) {
            throw new IllegalArgumentException("主键列数与值数不一致");
        }
        String whereClause = String.join(" AND ", pkColumns.stream().map(c -> c + " = ?").toList());
        return List.of(new ReverseStatement(
                "DELETE FROM " + tableName + " WHERE " + whereClause, List.copyOf(pkValues)));
    }
}
