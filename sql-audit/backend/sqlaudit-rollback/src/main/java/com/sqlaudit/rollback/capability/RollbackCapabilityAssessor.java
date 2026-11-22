package com.sqlaudit.rollback.capability;

import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnavailableReason;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RollbackPlan;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.TableMetadataProvider;

import java.util.List;

/**
 * 回滚能力评估器（设计文档 §5.1 / §5.2 覆盖率矩阵）——提交预览时产出
 * 「本单可回滚 / 不可回滚」清单的判定核心。
 *
 * <p>能力边界立场（§5.1）：提交预览时给出清单，不可回滚的高危语句按策略拒绝执行
 * 或强制兜底。事后才发现「回滚不了」是不可接受的设计缺陷。</p>
 *
 * <p>判定信号复用 A-2 规则命中（不重复实现）：R_NO_PK → 无主键；R_NONDETERMINISTIC_SET →
 * 前镜像失效；R_MULTI_TABLE_DML / R_LIMIT_ORDER_DML → 行集无法对齐。本类只补
 * 规则层不管的维度：INSERT 形态（单条/批量/INSERT...SELECT/ON DUPLICATE/REPLACE）、
 * DDL 可逆映射、方言差异（PG RETURNING 多行可覆盖）。</p>
 *
 * <p>注意：本类只做<strong>能力判定</strong>（预览）；前镜像在 Worker 执行时刻、
 * 同一连接同一事务内采集（§5.3），审核期绝不提前生成。</p>
 */
public class RollbackCapabilityAssessor {

    /** 评估一个 unit 的回滚能力。 */
    public RollbackPlan assess(CapabilityContext ctx) {
        ParsedStatement ps = ctx.statement();
        List<RuleHit> hits = ctx.hits() == null ? List.of() : ctx.hits();

        // 解析失败 → 不可回滚（§4.1 铁律的回滚侧投影）
        if (ps.getParseConfidence() == 0) {
            return none(UnavailableReason.PARSE_FAILED, "语句无法解析，无法判定回滚能力");
        }

        // IoTDB：不承诺自动回滚（§5.7，快照恢复 SOP）
        if (ps.getDialect() == Dialect.IOTDB) {
            return plan(RollbackCoverage.NONE, UnavailableReason.IOTDB_UNSUPPORTED, "SNAPSHOT",
                    "IoTDB 无行级反向操作，走元数据快照 + 恢复 SOP");
        }

        return switch (ps.getStatementType()) {
            case UPDATE, DELETE -> assessUpdateDelete(hits);
            case INSERT -> assessInsert(ctx, hits);
            case DDL -> assessDdl(hits);
            default -> none(UnavailableReason.PARSE_FAILED,
                    "不支持的语句类型: " + ps.getStatementType());
        };
    }

    // ------------------------------------------------------------------
    // UPDATE / DELETE：前镜像 → 反向 DML（§5.2 第 1、2 行 + 硬拒绝行）
    // ------------------------------------------------------------------

    private RollbackPlan assessUpdateDelete(List<RuleHit> hits) {
        // 规则命中转译为不可回滚原因（顺序即优先级）
        if (has(hits, "R_NO_PK")) {
            return none(UnavailableReason.NO_PRIMARY_KEY, "目标表无主键，无法生成反向 DML（硬拒绝）");
        }
        if (has(hits, "R_MULTI_TABLE_DML")) {
            return none(UnavailableReason.MULTI_TABLE_DML, "多表 JOIN 的 DML，前镜像行集无法与语句对齐");
        }
        if (has(hits, "R_LIMIT_ORDER_DML")) {
            return none(UnavailableReason.LIMIT_ORDER_DML, "带 LIMIT/ORDER BY 的 DML，回滚不可靠");
        }
        if (has(hits, "R_NONDETERMINISTIC_SET")) {
            return none(UnavailableReason.NONDETERMINISTIC, "SET 值非确定性（函数/自增/子查询），前镜像失效");
        }

        // 有主键 + 确定性 SET + 单表 → FULL（MySQL/PG 一致，§5.2 第 1、2 行）。
        // 单表前提：多表已由 R_MULTI_TABLE_DML 捕获；无主键由 R_NO_PK 捕获。
        return plan(RollbackCoverage.FULL, null, "MIRROR",
                "前镜像 → 每行一条反向 DML");
    }

    // ------------------------------------------------------------------
    // INSERT（§5.2 第 3、4 行）：单条可逆，批量形态按方言分流
    // ------------------------------------------------------------------

    private RollbackPlan assessInsert(CapabilityContext ctx, List<RuleHit> hits) {
        ParsedStatement ps = ctx.statement();
        Object src = ps.getSourceStatement();

        // REPLACE（MySQL）：整行替换语义，被替换的旧行丢失 → 不可回滚
        if (src instanceof SQLReplaceStatement) {
            return none(UnavailableReason.BATCH_INSERT, "REPLACE 语义会丢弃被替换旧行，标 not_available");
        }
        if (!(src instanceof SQLInsertStatement insert)) {
            return none(UnavailableReason.PARSE_FAILED, "无法识别的 INSERT 形态");
        }

        // INSERT ... SELECT：结果集不可枚举 → 不可回滚（§5.2 第 4 行）
        if (insert.getQuery() != null) {
            return none(UnavailableReason.BATCH_INSERT, "INSERT...SELECT 结果集不可枚举，转 binlog 闪回 / 拒绝");
        }

        // ON DUPLICATE KEY UPDATE（MySQL）：混合语义（insert-or-update）→ 不可回滚
        if (insert instanceof MySqlInsertStatement mysql && !mysql.getDuplicateKeyUpdate().isEmpty()) {
            return none(UnavailableReason.BATCH_INSERT,
                    "ON DUPLICATE KEY UPDATE 混合语义，反向无法区分插入/更新行");
        }

        int valueRowCount = insert.getValuesList() == null ? 0 : insert.getValuesList().size();

        // 显式主键的单条 INSERT → FULL（反向 DELETE WHERE pk=?）
        if (valueRowCount == 1 && explicitPkValue(insert, ctx.metadata())) {
            return plan(RollbackCoverage.FULL, null, "MIRROR",
                    "单条 INSERT 且显式主键 → 反向 DELETE WHERE pk=?");
        }

        // 无显式主键的单条自增 INSERT：MySQL 用 LAST_INSERT_ID()（仅当前连接、仅单条，
        // §5.4 已知限制）；PG 用 INSERT ... RETURNING pk（执行期改写）。都可精确取主键。
        if (valueRowCount == 1) {
            String note = ps.getDialect() == Dialect.MYSQL
                    ? "单条自增 INSERT → 执行后同连接 SELECT LAST_INSERT_ID() 取主键，反向 DELETE"
                    : "单条自增 INSERT → 执行期改写 INSERT ... RETURNING pk 取主键，反向 DELETE";
            return plan(RollbackCoverage.FULL, null, "MIRROR", note);
        }

        // 批量 INSERT：MySQL ❌；PG ⚠️ RETURNING 多行可覆盖（PARTIAL，§5.2 第 4 行）
        if (valueRowCount > 1) {
            if (ps.getDialect() == Dialect.PG) {
                return plan(RollbackCoverage.PARTIAL, null, "MIRROR",
                        "批量 INSERT：执行期改写 RETURNING pk 返回全部主键，逐行反向 DELETE"
                                + "（S2 spike 验证驱动多行 RETURNING 行为）");
            }
            return none(UnavailableReason.BATCH_INSERT,
                    "MySQL 批量 INSERT 并联写入打断自增连续性，LAST_INSERT_ID() 仅单条有效"
                            + "（§5.4 已知限制），转 binlog 闪回 / 拒绝");
        }

        return none(UnavailableReason.PARSE_FAILED, "INSERT 无 VALUES 子句");
    }

    /** INSERT 列清单里是否含主键列（与元数据主键比对）。 */
    private boolean explicitPkValue(SQLInsertStatement insert, TableMetadataProvider metadata) {
        TableMetadataProvider.TableMetadata tm = targetTable(insert, metadata);
        if (tm == null || tm.primaryKeyColumns().isEmpty()) {
            return false;
        }
        for (var col : insert.getColumns()) {
            String name = col instanceof SQLName n ? n.getSimpleName() : String.valueOf(col);
            if (tm.primaryKeyColumns().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private TableMetadataProvider.TableMetadata targetTable(SQLInsertStatement insert,
                                                             TableMetadataProvider metadata) {
        if (metadata == null || !metadata.isAvailable() || insert.getTableSource() == null) {
            return null;
        }
        SQLExprTableSource ts = insert.getTableSource();
        return metadata.getTable(ts.getSchema(), ts.getTableName());
    }

    // ------------------------------------------------------------------
    // DDL（§5.2 第 7、8 行 + §5.6 映射表）
    // ------------------------------------------------------------------

    private RollbackPlan assessDdl(List<RuleHit> hits) {
        // 破坏性 DDL：影子表 / 列投影备份，不生成反向 SQL（§5.6）
        if (has(hits, "R_DDL_DESTRUCTIVE")) {
            return plan(RollbackCoverage.NONE, UnavailableReason.DESTRUCTIVE_DDL, "SNAPSHOT",
                    "破坏性 DDL 走影子表（整表级）/ 列投影备份（列级），不生成反向 SQL");
        }
        // 其余 DDL 按可逆向候选处理；具体映射（CREATE TABLE/ADD COLUMN/CREATE INDEX/RENAME
        // → 反向 DDL）由 DdlReverseMapper 产出，不认识的形态在那里标 not_available
        return plan(RollbackCoverage.FULL, null, "REVERSE_DDL",
                "可逆向 DDL → 反向 DDL（带有效期与前置条件，§5.6）");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static boolean has(List<RuleHit> hits, String code) {
        return hits.stream().anyMatch(h -> code.equals(h.getCode()));
    }

    private static RollbackPlan plan(RollbackCoverage coverage, UnavailableReason reason,
                                     String generator, String note) {
        RollbackPlan p = new RollbackPlan();
        p.setCoverage(coverage);
        p.setUnavailableReason(reason);
        p.setGenerator(generator);
        p.getReverseStatementPreview().add(note);
        return p;
    }

    private static RollbackPlan none(UnavailableReason reason, String note) {
        return plan(RollbackCoverage.NONE, reason, "NONE", note);
    }
}
