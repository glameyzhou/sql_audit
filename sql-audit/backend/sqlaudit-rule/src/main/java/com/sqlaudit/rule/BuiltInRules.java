package com.sqlaudit.rule;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.StatementType;

import java.util.List;
import java.util.Map;

/**
 * 内置规则清单（设计文档 §4.3，首版 27 条）。
 *
 * <p>非可覆盖（nonOverridable）的规则：破坏性 DDL / 回滚不可用 / 解析失败 /
 * 影响面超限——这四类是回滚能力与生产安全的硬底线，任何 flow_rule 配置都不能降级
 * （§4.5 路由算法）。</p>
 *
 * <p>阈值默认值（§4.3 / §4.4 / §5.3）：阻断 10 万行、低峰建议 1 千行、
 * 单工单 unit 上限 200、前镜像上限 5000 行。</p>
 */
public final class BuiltInRules {

    private BuiltInRules() {
    }

    /** 按 code 查找内置规则定义；不存在返回 null。 */
    public static RuleDefinition byCode(RuleCode code) {
        for (RuleDefinition r : ALL) {
            if (r.code() == code) {
                return r;
            }
        }
        return null;
    }

    /** §4.3 内置规则全表（code 升序，27 条；R_INDEX_NAME / R_TABLE_NAME 各算一条）。 */
    public static final List<RuleDefinition> ALL = List.of(
                def(RuleCode.R_AFFECT_TOO_MANY, null, null, RuleSeverity.ERROR, true,
                        Map.of("threshold", 100_000L), "影响行数超阈值（默认 10 万），强制 DBA 节点"),
                def(RuleCode.R_BIG_UNIT_COUNT, null, null, RuleSeverity.WARN, false,
                        Map.of("threshold", 200L), "单工单 unit 数超阈值（默认 200）"),
                def(RuleCode.R_DDL_DESTRUCTIVE, null, StatementType.DDL, RuleSeverity.ERROR, true,
                        Map.of(), "DROP / TRUNCATE / DROP COLUMN / MODIFY COLUMN，走影子表流程"),
                def(RuleCode.R_DDL_NO_IF_EXISTS, null, StatementType.DDL, RuleSeverity.WARN, false,
                        Map.of(), "危险 DDL 未带幂等保护（不自动改写用户 SQL）"),
                def(RuleCode.R_FULL_TABLE_SCAN, null, null, RuleSeverity.WARN, false,
                        Map.of(), "WHERE 列无可用索引（EXPLAIN 提示，需元数据）"),
                def(RuleCode.R_IOTDB_DROP, Dialect.IOTDB, StatementType.DDL, RuleSeverity.ERROR, true,
                        Map.of(), "IoTDB DROP DATABASE / TIMESERIES，强制人工审批 + 元数据快照"),
                def(RuleCode.R_IOTDB_NO_TIME_RANGE, Dialect.IOTDB, StatementType.DELETE, RuleSeverity.ERROR, true,
                        Map.of(), "IoTDB DELETE 无时间范围"),
                def(RuleCode.R_IOTDB_TTL, Dialect.IOTDB, null, RuleSeverity.WARN, false,
                        Map.of(), "IoTDB TTL / 模板变更"),
                def(RuleCode.R_INDEX_NAME, null, StatementType.DDL, RuleSeverity.WARN, false,
                        Map.of("pattern", "idx_.*"), "索引命名规范"),
                def(RuleCode.R_LIMIT_ORDER_DML, null, null, RuleSeverity.ERROR, true,
                        Map.of(), "UPDATE/DELETE 带 LIMIT/ORDER BY（回滚不可靠）"),
                def(RuleCode.R_LOW_PEAK_WINDOW, null, null, RuleSeverity.WARN, false,
                        Map.of("threshold", 1_000L), "影响行数超「建议低峰」阈值（默认 1 千）但未达阻断（v2.1 与 R_AFFECT_TOO_MANY 拆分）"),
                def(RuleCode.R_MULTI_TABLE_DML, null, null, RuleSeverity.ERROR, true,
                        Map.of(), "多表 JOIN UPDATE/DELETE"),
                def(RuleCode.R_NO_LIMIT_QUERY, null, StatementType.SELECT, RuleSeverity.ERROR, false,
                        Map.of(), "查询通道无 LIMIT（平台自动补 LIMIT；查询通道执行期规则）"),
                def(RuleCode.R_NO_PK, null, null, RuleSeverity.ERROR, true,
                        Map.of(), "目标表无主键（影响回滚能力，需元数据）"),
                def(RuleCode.R_NO_WHERE, null, null, RuleSeverity.ERROR, false,
                        Map.of(), "UPDATE/DELETE 无 WHERE"),
                def(RuleCode.R_NONDETERMINISTIC_SET, null, StatementType.UPDATE, RuleSeverity.ERROR, true,
                        Map.of(), "SET c=c+1 / NOW() / 子查询（前镜像失效，标不可回滚）"),
                def(RuleCode.R_OBJECT_NOT_ALLOWED, null, null, RuleSeverity.ERROR, false,
                        Map.of(), "目标对象不在实例白名单（需元数据）"),
                def(RuleCode.R_PARSE_FAILED, null, null, RuleSeverity.ERROR, true,
                        Map.of(), "解析失败，人工审"),
                def(RuleCode.R_PREIMAGE_TOO_LARGE, null, null, RuleSeverity.ERROR, true,
                        Map.of("rowLimit", 5_000L, "bytesLimit", 16_777_216L), "前镜像行数/字节超上限（§5.3），转分片/binlog/快照"),
                def(RuleCode.R_ROLLBACK_UNAVAILABLE, null, null, RuleSeverity.ERROR, true,
                        Map.of("strategy", "REJECT"), "无法生成回滚 SQL（策略可配：拒绝/强制兜底）"),
                def(RuleCode.R_SCHEMA_DRIFT, null, null, RuleSeverity.ERROR, true,
                        Map.of(), "执行前 DDL 指纹与审核时不一致（§3.5 执行期规则，提交期不触发）"),
                def(RuleCode.R_SELECT_STAR, null, StatementType.INSERT, RuleSeverity.WARN, false,
                        Map.of(), "SELECT * 于变更语句（INSERT...SELECT）"),
                def(RuleCode.R_SENSITIVE_COLUMN, null, null, RuleSeverity.ERROR, false,
                        Map.of(), "命中掩码列且未授权（需元数据）"),
                def(RuleCode.R_SQL_INJECTION_PATTERN, null, null, RuleSeverity.ERROR, false,
                        Map.of(), "拼接痕迹 / 注释绕过"),
                def(RuleCode.R_TABLE_NAME, null, StatementType.DDL, RuleSeverity.WARN, false,
                        Map.of("pattern", "^[a-z][a-z0-9_]{0,63}$"), "表命名规范"),
                def(RuleCode.R_TX_DDL_MIX, null, null, RuleSeverity.WARN, false,
                        Map.of(), "单工单混合 DDL 与 DML"));

    private static RuleDefinition def(RuleCode code, Dialect dialect, StatementType type,
                                      RuleSeverity severity, boolean nonOverridable,
                                      Map<String, Object> params, String description) {
        return new RuleDefinition(code, dialect, type, severity, nonOverridable, params, description);
    }
}
