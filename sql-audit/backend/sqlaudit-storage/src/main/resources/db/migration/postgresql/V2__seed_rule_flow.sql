-- ============================================================================
-- V2 · 系统配置种子：审核规则（26 条内置）+ 审批流（L1 全局默认 4 级）
-- 口径：与代码 BuiltInRules / FlowConfig 的默认值一一对应（2026-11-23）。
-- 审核规则是展示与配置基线；真正的执行判定仍以代码内 BuiltInRules 为准（含
-- 4 条 nonOverridable 硬底线），本表修改不改变运行时行为（W2 后段接覆盖）。
-- ============================================================================

-- 1) 审核规则（t_rule）：dialect/statement_type 为 ALL 表示不限
INSERT INTO t_rule (code, dialect, statement_type, severity, enabled, params, description) VALUES
  ('R_AFFECT_TOO_MANY', 'ALL', 'ALL', 'ERROR', 1, '{"threshold":100000}', '影响行数超阈值（默认 10 万），强制 DBA 节点'),
  ('R_BIG_UNIT_COUNT', 'ALL', 'ALL', 'WARN', 1, '{"threshold":200}', '单工单 unit 数超阈值（默认 200）'),
  ('R_DDL_DESTRUCTIVE', 'ALL', 'DDL', 'ERROR', 1, NULL, 'DROP / TRUNCATE / DROP COLUMN / MODIFY COLUMN，走影子表流程'),
  ('R_DDL_NO_IF_EXISTS', 'ALL', 'DDL', 'WARN', 1, NULL, '危险 DDL 未带幂等保护（不自动改写用户 SQL）'),
  ('R_FULL_TABLE_SCAN', 'ALL', 'ALL', 'WARN', 1, NULL, 'WHERE 列无可用索引（EXPLAIN 提示，需元数据）'),
  ('R_IOTDB_DROP', 'IOTDB', 'DDL', 'ERROR', 1, NULL, 'IoTDB DROP DATABASE / TIMESERIES，强制人工审批 + 元数据快照'),
  ('R_IOTDB_NO_TIME_RANGE', 'IOTDB', 'DELETE', 'ERROR', 1, NULL, 'IoTDB DELETE 无时间范围'),
  ('R_IOTDB_TTL', 'IOTDB', 'ALL', 'WARN', 1, NULL, 'IoTDB TTL / 模板变更'),
  ('R_INDEX_NAME', 'ALL', 'DDL', 'WARN', 1, '{"pattern":"idx_.*"}', '索引命名规范'),
  ('R_LIMIT_ORDER_DML', 'ALL', 'ALL', 'ERROR', 1, NULL, 'UPDATE/DELETE 带 LIMIT/ORDER BY（回滚不可靠）'),
  ('R_LOW_PEAK_WINDOW', 'ALL', 'ALL', 'WARN', 1, '{"threshold":1000}', '影响行数超「建议低峰」阈值（默认 1 千）但未达阻断'),
  ('R_MULTI_TABLE_DML', 'ALL', 'ALL', 'ERROR', 1, NULL, '多表 JOIN UPDATE/DELETE'),
  ('R_NO_LIMIT_QUERY', 'ALL', 'SELECT', 'ERROR', 1, NULL, '查询通道无 LIMIT（平台自动补 LIMIT；查询通道执行期规则）'),
  ('R_NO_PK', 'ALL', 'ALL', 'ERROR', 1, NULL, '目标表无主键（影响回滚能力，需元数据）'),
  ('R_NO_WHERE', 'ALL', 'ALL', 'ERROR', 1, NULL, 'UPDATE/DELETE 无 WHERE'),
  ('R_NONDETERMINISTIC_SET', 'ALL', 'UPDATE', 'ERROR', 1, NULL, 'SET c=c+1 / NOW() / 子查询（前镜像失效，标不可回滚）'),
  ('R_OBJECT_NOT_ALLOWED', 'ALL', 'ALL', 'ERROR', 1, NULL, '目标对象不在实例白名单（需元数据）'),
  ('R_PARSE_FAILED', 'ALL', 'ALL', 'ERROR', 1, NULL, '解析失败，人工审'),
  ('R_PREIMAGE_TOO_LARGE', 'ALL', 'ALL', 'ERROR', 1, '{"rowLimit":5000,"bytesLimit":16777216}', '前镜像行数/字节超上限（§5.3），转分片/binlog/快照'),
  ('R_ROLLBACK_UNAVAILABLE', 'ALL', 'ALL', 'ERROR', 1, '{"strategy":"REJECT"}', '无法生成回滚 SQL（策略可配：拒绝/强制兜底）'),
  ('R_SCHEMA_DRIFT', 'ALL', 'ALL', 'ERROR', 1, NULL, '执行前 DDL 指纹与审核时不一致（§3.5 执行期规则，提交期不触发）'),
  ('R_SELECT_STAR', 'ALL', 'INSERT', 'WARN', 1, NULL, 'SELECT * 于变更语句（INSERT...SELECT）'),
  ('R_SENSITIVE_COLUMN', 'ALL', 'ALL', 'ERROR', 1, NULL, '命中掩码列且未授权（需元数据）'),
  ('R_SQL_INJECTION_PATTERN', 'ALL', 'ALL', 'ERROR', 1, NULL, '拼接痕迹 / 注释绕过'),
  ('R_TABLE_NAME', 'ALL', 'DDL', 'WARN', 1, '{"pattern":"^[a-z][a-z0-9_]{0,63}$"}', '表命名规范'),
  ('R_TX_DDL_MIX', 'ALL', 'ALL', 'WARN', 1, NULL, '单工单混合 DDL 与 DML');

-- 2) 审批流（t_flow_rule）：L1 全局默认（instance_id IS NULL），与 FlowConfig 代码默认一致
INSERT INTO t_flow_rule (name, environment, instance_id, risk_level, non_overridable, priority, enabled, created_by) VALUES
  ('L1 全局默认 · LOW', 'ALL', NULL, 'LOW', 0, 0, 1, 1),
  ('L1 全局默认 · MEDIUM', 'ALL', NULL, 'MEDIUM', 0, 0, 1, 1),
  ('L1 全局默认 · HIGH', 'ALL', NULL, 'HIGH', 0, 0, 1, 1),
  ('L1 全局默认 · CRITICAL', 'ALL', NULL, 'CRITICAL', 1, 0, 1, 1);

-- 3) 审批节点（t_flow_node）：LOW 无节点=免审；MEDIUM=LEAD；HIGH=LEAD→DBA；
--    CRITICAL=LEAD→DBA→ADMIN 且不可被任何配置降级（L3 兜底口径）
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 1, 'ROLE', 'LEAD', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · MEDIUM' AND instance_id IS NULL;

INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 1, 'ROLE', 'LEAD', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · HIGH' AND instance_id IS NULL;
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 2, 'ROLE', 'DBA', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · HIGH' AND instance_id IS NULL;

INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 1, 'ROLE', 'LEAD', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · CRITICAL' AND instance_id IS NULL;
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 2, 'ROLE', 'DBA', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · CRITICAL' AND instance_id IS NULL;
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode, timeout_minutes)
SELECT id, 3, 'ROLE', 'ADMIN', 'OR', 1440 FROM t_flow_rule
  WHERE name = 'L1 全局默认 · CRITICAL' AND instance_id IS NULL;
