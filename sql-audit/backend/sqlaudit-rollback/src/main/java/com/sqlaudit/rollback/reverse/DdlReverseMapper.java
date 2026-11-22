package com.sqlaudit.rollback.reverse;

import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAddColumn;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateIndexStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.sqlaudit.common.enums.Dialect;

import java.util.List;
import java.util.Optional;

/**
 * DDL 逆向映射（设计文档 §5.6 可逆向映射表）。
 *
 * <table>
 *   <tr><th>原 DDL</th><th>反向</th><th>前置条件</th></tr>
 *   <tr><td>CREATE TABLE t</td><td>DROP TABLE IF EXISTS t</td><td>窗口内未被写入（否则丢数据）</td></tr>
 *   <tr><td>ALTER TABLE t ADD COLUMN c</td><td>ALTER TABLE t DROP COLUMN c</td><td>窗口内 c 列无新写入</td></tr>
 *   <tr><td>CREATE INDEX i ON t</td><td>MySQL: ALTER TABLE t DROP INDEX i / PG: DROP INDEX i</td><td>无</td></tr>
 *   <tr><td>RENAME TABLE a TO b</td><td>RENAME TABLE b TO a</td><td>应用未开始用新名写入</td></tr>
 * </table>
 *
 * <p>破坏性 DDL（DROP/TRUNCATE/DROP COLUMN/MODIFY）不走本映射（影子表/列投影，
 * 能力评估已判 NONE）。不认识的 DDL 形态返回 empty → 上层标 not_available，
 * 绝不猜测（§5.6：反向 DDL 带前置条件，宁可拒绝也不生成错误语句）。</p>
 *
 * <p>变更窗口写入检测（RENAME/CREATE 的前置条件）在回滚执行前做
 * （binlog / 审计 / 行数比对），本类只产出语句与前置条件描述。</p>
 */
public class DdlReverseMapper {

    /** 逆向结果：反向 DDL + 前置条件说明（回滚执行前必须校验）。 */
    public record DdlReverse(String reverseSql, String precondition) {
    }

    /**
     * 生成反向 DDL；不认识的形态返回 empty（上层标 not_available）。
     *
     * @param sourceStatement parser 挂载的原始 Druid AST 节点
     */
    public Optional<DdlReverse> map(Object sourceStatement, Dialect dialect) {
        // CREATE TABLE → DROP TABLE IF EXISTS（保留 IF EXISTS：回滚时表可能在人工干预下已删）
        if (sourceStatement instanceof SQLCreateTableStatement create) {
            String table = create.getTableSource() != null
                    ? create.getTableSource().getTableName() : null;
            if (table == null) {
                return Optional.empty();
            }
            return Optional.of(new DdlReverse(
                    "DROP TABLE IF EXISTS " + table,
                    "回滚执行前检查变更窗口内该表未被写入（否则反向 DROP 会丢新数据）"));
        }

        // ALTER TABLE ADD COLUMN → DROP COLUMN
        if (sourceStatement instanceof SQLAlterTableStatement alter) {
            String table = alter.getTableSource() != null
                    ? alter.getTableSource().getTableName() : null;
            if (table == null) {
                return Optional.empty();
            }
            // 多 item 的 ALTER（如两条 ADD COLUMN）逆向无法逐条完整覆盖，
            // 只映射第一条会生成不完整反向 SQL——一律拒映射转人工（§5.2 矩阵口径）
            if (alter.getItems().size() != 1) {
                return Optional.empty();
            }
            SQLAlterTableItem item = alter.getItems().get(0);
            if (item instanceof SQLAlterTableAddColumn add) {
                List<com.alibaba.druid.sql.ast.statement.SQLColumnDefinition> columns = add.getColumns();
                if (columns.size() != 1) {
                    return Optional.empty(); // 单条 ADD 多列同括号形态，首版不支持
                }
                String col = columns.get(0).getColumnName();
                return Optional.of(new DdlReverse(
                        "ALTER TABLE " + table + " DROP COLUMN " + col,
                        "回滚执行前检查变更窗口内 " + col + " 列无新写入"));
            }
            return Optional.empty(); // 其他 ALTER 形态（含破坏性，已在上游分流）
        }

        // CREATE INDEX → 按方言 DROP
        if (sourceStatement instanceof SQLCreateIndexStatement createIndex) {
            String index = createIndex.getName() != null ? createIndex.getName().getSimpleName() : null;
            String table = createIndex.getTable() != null
                    && createIndex.getTable() instanceof com.alibaba.druid.sql.ast.statement.SQLExprTableSource ts
                    ? ts.getTableName() : null;
            if (index == null || table == null) {
                return Optional.empty();
            }
            String reverse = dialect == Dialect.PG
                    ? "DROP INDEX " + index
                    : "ALTER TABLE " + table + " DROP INDEX " + index;
            return Optional.of(new DdlReverse(reverse, "无"));
        }

        // RENAME TABLE a TO b（MySQL 方言类；PG 是 ALTER TABLE RENAME，属 ALTER 分支外的形态）
        String cls = sourceStatement == null ? "" : sourceStatement.getClass().getSimpleName();
        if (cls.contains("Rename")) {
            // MySQL RENAME TABLE 的 AST 形态不通用，按归一化文本兜底解析
            return mapRenameByText(String.valueOf(sourceStatement));
        }

        return Optional.empty();
    }

    /** RENAME 文本兜底：{@code RENAME TABLE a TO b} → {@code RENAME TABLE b TO a}。 */
    private Optional<DdlReverse> mapRenameByText(String normalized) {
        // 归一化输出形如 "RENAME TABLE a TO b"；只接受可完整解析的形态，否则拒绝
        String lower = normalized.toLowerCase();
        if (!lower.startsWith("rename table")) {
            return Optional.empty();
        }
        String[] parts = normalized.trim().split("\\s+");
        // RENAME TABLE <a> TO <b>
        if (parts.length != 5 || !"to".equalsIgnoreCase(parts[3])) {
            return Optional.empty();
        }
        return Optional.of(new DdlReverse(
                "RENAME TABLE " + parts[4] + " TO " + parts[2],
                "回滚执行前检查应用未开始用新表名写入（视图/外键/存储过程按名引用需先扫依赖）"));
    }
}
