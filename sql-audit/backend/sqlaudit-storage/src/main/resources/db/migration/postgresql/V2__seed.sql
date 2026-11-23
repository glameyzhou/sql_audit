-- =====================================================================
-- SQL 审核平台 · 平台库种子数据（Flyway V2 —— 只含 DML）
--
-- 本文件在 mysql / postgresql 两个目录下【逐字节相同】
-- （SeedDataEnumContractTest 会断言这一点）：只用标准 INSERT ... VALUES /
-- INSERT ... SELECT，布尔一律写 TRUE / FALSE，跨表引用一律按 code / username
-- 关联，绝不写自增 id——换方言、换插入顺序都不会错位。
--
-- 书写约束（测试基建按「行尾分号」拆分语句重放本文件）：
--   每条语句以行尾 `;` 结束，语句中间不得出现行尾分号。
--
-- 内容：
--   1. 审计链创世块
--   2. 5 个内置角色
--   3. 13 个权限点 + 59 条接口绑定
--   4. 角色 → 权限点默认授权
--   5. admin / dev 本地账号与角色
--   6. 26 条内置审核规则
--   7. 4 条全局审批流与节点
-- =====================================================================
-- ---------------------------------------------------------------------
-- 1. 审计链创世块（last_hash = 64 个 '0'）
-- ---------------------------------------------------------------------
INSERT INTO t_chain_head (id, last_seq, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');
-- ---------------------------------------------------------------------
-- 2. 内置角色（builtin=TRUE 仅表示不可删除 / code 不可改，不参与鉴权判定）
--    超级管理员 = 持有全部权限点的普通角色，代码里没有任何「超管」分支。
-- ---------------------------------------------------------------------
INSERT INTO t_role (code, name, description, builtin, instance_scope) VALUES
  ('SUPER_ADMIN', '超级管理员', '全部权限点（含元权限：角色与权限管理）', TRUE, 'ALL'),
  ('ADMIN', '管理员', '除元权限外的全部配置能力', TRUE, 'ALL'),
  ('DBA', 'DBA', '执行/二次确认、回滚、审计、规则与流程配置', TRUE, 'ALL'),
  ('LEAD', '主管', '提交、查询（审批资格来自审批链，不来自角色）', TRUE, 'ALL'),
  ('DEV', '开发', '提交、查询', TRUE, 'ALL');
-- ---------------------------------------------------------------------
-- 3. 权限点目录（界面只读；code 供本文件引用，代码不引用）
--    all_users=TRUE：所有登录用户默认拥有，不写 t_role_permission 授权行
--    meta=TRUE：防自锁不变式的对象
-- ---------------------------------------------------------------------
INSERT INTO t_permission (code, name, module, description, sort, all_users, meta) VALUES
  ('basic', '登录用户基础功能', '基础', '工作台、我的工单、待我审批、人员与角色下拉', 10, TRUE, FALSE),
  ('ticket.submit', '提交变更', '变更', '预览、建单、提交审批、撤销', 20, FALSE, FALSE),
  ('ticket.view_all', '查看全部工单', '变更', '查看数据范围内所有人的工单', 30, FALSE, FALSE),
  ('approval.confirm', '执行前二次确认', '变更', 'CRITICAL 工单执行前的二次确认', 40, FALSE, FALSE),
  ('execution.view', '执行监控', '变更', '执行单元状态看板', 50, FALSE, FALSE),
  ('rollback.view', '回滚查看', '变更', '回滚清单与反向语句明细（含前镜像旧值）', 60, FALSE, FALSE),
  ('rollback.execute', '回滚执行', '变更', '执行回滚', 70, FALSE, FALSE),
  ('query.run', '线上查询', '查询与审计', '只读查询通道（带 LIMIT 保护与脱敏）', 80, FALSE, FALSE),
  ('audit.view', '审计日志', '查询与审计', '审计日志检索与 hash 链校验', 90, FALSE, FALSE),
  ('rule.manage', '审核规则配置', '配置', '审核规则增删改查', 100, FALSE, FALSE),
  ('flow.manage', '审批流配置', '配置', '审批流程与节点配置', 110, FALSE, FALSE),
  ('instance.manage', '实例与凭据管理', '配置', '实例接入、凭据维护、连通性测试', 120, FALSE, FALSE),
  ('user.manage', '用户管理', '系统管理', '账号创建、资料与状态、密码重置', 130, FALSE, FALSE),
  ('rbac.manage', '角色与权限管理', '系统管理', '角色增删改、权限与数据范围授权、用户角色分配', 140, FALSE, TRUE);
-- 权限点 → 接口绑定（HTTP 方法 + Spring 处理器模式原文；路径变量名与 Controller 完全一致）
-- 同一接口可绑多个权限点，持有任一即放行。
-- basic（所有登录用户）
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/auth/me' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/tickets' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/tickets/{id}' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/executions/ticket/{ticketId}' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/approvals/pending' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/approvals/{ticketId}/decide' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/approvals/{ticketId}/transfer' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/directory/users' FROM t_permission p WHERE p.code = 'basic';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/directory/roles' FROM t_permission p WHERE p.code = 'basic';
-- ticket.submit
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/tickets/preview' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/tickets' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/tickets/{id}/cancel' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases/{db}/tables' FROM t_permission p WHERE p.code = 'ticket.submit';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases/{db}/columns' FROM t_permission p WHERE p.code = 'ticket.submit';
-- ticket.view_all
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/tickets/all' FROM t_permission p WHERE p.code = 'ticket.view_all';
-- approval.confirm
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/approvals/{ticketId}/confirm-execution' FROM t_permission p WHERE p.code = 'approval.confirm';
-- execution.view
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/executions' FROM t_permission p WHERE p.code = 'execution.view';
-- rollback.view
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/rollbacks' FROM t_permission p WHERE p.code = 'rollback.view';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/rollbacks/{id}/statements' FROM t_permission p WHERE p.code = 'rollback.view';
-- rollback.execute（列表也要能看，否则进不了回滚页）
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/rollbacks' FROM t_permission p WHERE p.code = 'rollback.execute';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/rollbacks/{id}/execute' FROM t_permission p WHERE p.code = 'rollback.execute';
-- query.run（查询页同样需要实例与库表元数据）
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/query' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases/{db}/tables' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases/{db}/columns' FROM t_permission p WHERE p.code = 'query.run';
-- audit.view
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/audit-logs' FROM t_permission p WHERE p.code = 'audit.view';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/audit-logs/verify' FROM t_permission p WHERE p.code = 'audit.view';
-- rule.manage
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/rules' FROM t_permission p WHERE p.code = 'rule.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/config/rules' FROM t_permission p WHERE p.code = 'rule.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/config/rules/{id}' FROM t_permission p WHERE p.code = 'rule.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'DELETE', '/api/config/rules/{id}' FROM t_permission p WHERE p.code = 'rule.manage';
-- flow.manage（节点编辑器要选实例范围，故含实例列表）
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/flows' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/config/flows' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/config/flows/{id}' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'DELETE', '/api/config/flows/{id}' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/flows/{id}/nodes' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/config/flows/{id}/nodes' FROM t_permission p WHERE p.code = 'flow.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/instances' FROM t_permission p WHERE p.code = 'flow.manage';
-- instance.manage
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/instances' FROM t_permission p WHERE p.code = 'instance.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/config/instances' FROM t_permission p WHERE p.code = 'instance.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/config/instances/{id}' FROM t_permission p WHERE p.code = 'instance.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'DELETE', '/api/config/instances/{id}' FROM t_permission p WHERE p.code = 'instance.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/instances/{id}/credentials' FROM t_permission p WHERE p.code = 'instance.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/instances/test-connection' FROM t_permission p WHERE p.code = 'instance.manage';
-- user.manage
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/users' FROM t_permission p WHERE p.code = 'user.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/users' FROM t_permission p WHERE p.code = 'user.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/users/{id}' FROM t_permission p WHERE p.code = 'user.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/users/{id}/password' FROM t_permission p WHERE p.code = 'user.manage';
-- rbac.manage（元权限；角色编辑弹窗的数据范围要选实例，故含实例列表）
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/roles' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'POST', '/api/roles' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/roles/{id}' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'DELETE', '/api/roles/{id}' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/permissions' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/users/{id}/roles' FROM t_permission p WHERE p.code = 'rbac.manage';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/config/instances' FROM t_permission p WHERE p.code = 'rbac.manage';
-- ---------------------------------------------------------------------
-- 4. 角色 → 权限点默认授权（all_users 的权限点不写授权行）
-- ---------------------------------------------------------------------
-- 超级管理员：全部权限点（含元权限 rbac.manage）
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r, t_permission p
WHERE r.code = 'SUPER_ADMIN' AND p.all_users = FALSE;
-- 管理员：除元权限外的全部配置能力
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r, t_permission p
WHERE r.code = 'ADMIN' AND p.all_users = FALSE AND p.meta = FALSE;
-- DBA：执行/二次确认、回滚、审计、规则与流程配置（无实例与用户管理）
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r, t_permission p
WHERE r.code = 'DBA' AND p.code IN ('ticket.submit', 'ticket.view_all', 'approval.confirm',
  'execution.view', 'rollback.view', 'rollback.execute', 'query.run', 'audit.view',
  'rule.manage', 'flow.manage');
-- 主管：提交与查询（审批资格来自审批链）
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r, t_permission p
WHERE r.code = 'LEAD' AND p.code IN ('ticket.submit', 'query.run');
-- 开发：提交与查询
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r, t_permission p
WHERE r.code = 'DEV' AND p.code IN ('ticket.submit', 'query.run');
-- ---------------------------------------------------------------------
-- 5. 本地账号（bcrypt 哈希由 BCryptPasswordEncoder 预先生成）
--    admin / admin12345 —— 生产环境首次登录后必须改密码
--    dev   / dev12345
--    admin 绑定全部 5 个内置角色：审批链的 ROLE 节点要求「本人持有该角色」，
--    代码不对超管做特判，所以超管必须真的持有这些角色才能审批。
-- ---------------------------------------------------------------------
INSERT INTO t_user (username, display_name, email, source, password_hash, status) VALUES
  ('admin', '超级管理员', NULL, 'LOCAL', '$2a$10$FiBBJ3pgO89qEMKeuPvLYOoLGyRxsrPPUNaCHj6RrJjcvl3htfBgS', 'ACTIVE'),
  ('dev', '开发', NULL, 'LOCAL', '$2a$10$1/PCcifeqvWTpPD3b2pEleY0y5MRa1469nQ5gJGcRhpCuxBBkZWSC', 'ACTIVE');
INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r
WHERE u.username = 'admin' AND r.code IN ('SUPER_ADMIN', 'ADMIN', 'DBA', 'LEAD', 'DEV');
INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r
WHERE u.username = 'dev' AND r.code = 'DEV';
-- ---------------------------------------------------------------------
-- 6. 内置审核规则（26 条，与代码 BuiltInRules 的 code 集合一一对应）
--    dialect / statement_type 为 ALL 表示不限；params 为 JSON 文本列（原样读写）
-- ---------------------------------------------------------------------
INSERT INTO t_rule (code, dialect, statement_type, severity, enabled, params, description) VALUES
  ('R_AFFECT_TOO_MANY', 'ALL', 'ALL', 'ERROR', TRUE, '{"threshold":100000}', '影响行数超阈值（默认 10 万），强制 DBA 节点'),
  ('R_BIG_UNIT_COUNT', 'ALL', 'ALL', 'WARN', TRUE, '{"threshold":200}', '单工单 unit 数超阈值（默认 200）'),
  ('R_DDL_DESTRUCTIVE', 'ALL', 'DDL', 'ERROR', TRUE, NULL, 'DROP / TRUNCATE / DROP COLUMN / MODIFY COLUMN，走影子表流程'),
  ('R_DDL_NO_IF_EXISTS', 'ALL', 'DDL', 'WARN', TRUE, NULL, '危险 DDL 未带幂等保护（不自动改写用户 SQL）'),
  ('R_FULL_TABLE_SCAN', 'ALL', 'ALL', 'WARN', TRUE, NULL, 'WHERE 列无可用索引（EXPLAIN 提示，需元数据）'),
  ('R_IOTDB_DROP', 'IOTDB', 'DDL', 'ERROR', TRUE, NULL, 'IoTDB DROP DATABASE / TIMESERIES，强制人工审批 + 元数据快照'),
  ('R_IOTDB_NO_TIME_RANGE', 'IOTDB', 'DELETE', 'ERROR', TRUE, NULL, 'IoTDB DELETE 无时间范围'),
  ('R_IOTDB_TTL', 'IOTDB', 'ALL', 'WARN', TRUE, NULL, 'IoTDB TTL / 模板变更'),
  ('R_INDEX_NAME', 'ALL', 'DDL', 'WARN', TRUE, '{"pattern":"idx_.*"}', '索引命名规范'),
  ('R_LIMIT_ORDER_DML', 'ALL', 'ALL', 'ERROR', TRUE, NULL, 'UPDATE/DELETE 带 LIMIT/ORDER BY（回滚不可靠）'),
  ('R_LOW_PEAK_WINDOW', 'ALL', 'ALL', 'WARN', TRUE, '{"threshold":1000}', '影响行数超「建议低峰」阈值（默认 1 千）但未达阻断'),
  ('R_MULTI_TABLE_DML', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '多表 JOIN UPDATE/DELETE'),
  ('R_NO_LIMIT_QUERY', 'ALL', 'SELECT', 'ERROR', TRUE, NULL, '查询通道无 LIMIT（平台自动补 LIMIT；查询通道执行期规则）'),
  ('R_NO_PK', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '目标表无主键（影响回滚能力，需元数据）'),
  ('R_NO_WHERE', 'ALL', 'ALL', 'ERROR', TRUE, NULL, 'UPDATE/DELETE 无 WHERE'),
  ('R_NONDETERMINISTIC_SET', 'ALL', 'UPDATE', 'ERROR', TRUE, NULL, 'SET c=c+1 / NOW() / 子查询（前镜像失效，标不可回滚）'),
  ('R_OBJECT_NOT_ALLOWED', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '目标对象不在实例白名单（需元数据）'),
  ('R_PARSE_FAILED', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '解析失败，人工审'),
  ('R_PREIMAGE_TOO_LARGE', 'ALL', 'ALL', 'ERROR', TRUE, '{"rowLimit":5000,"bytesLimit":16777216}', '前镜像行数/字节超上限，转分片/binlog/快照'),
  ('R_ROLLBACK_UNAVAILABLE', 'ALL', 'ALL', 'ERROR', TRUE, '{"strategy":"REJECT"}', '无法生成回滚 SQL（策略可配：拒绝/强制兜底）'),
  ('R_SCHEMA_DRIFT', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '执行前 DDL 指纹与审核时不一致（执行期规则，提交期不触发）'),
  ('R_SELECT_STAR', 'ALL', 'INSERT', 'WARN', TRUE, NULL, 'SELECT * 于变更语句（INSERT...SELECT）'),
  ('R_SENSITIVE_COLUMN', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '命中掩码列且未授权（需元数据）'),
  ('R_SQL_INJECTION_PATTERN', 'ALL', 'ALL', 'ERROR', TRUE, NULL, '拼接痕迹 / 注释绕过'),
  ('R_TABLE_NAME', 'ALL', 'DDL', 'WARN', TRUE, '{"pattern":"^[a-z][a-z0-9_]{0,63}$"}', '表命名规范'),
  ('R_TX_DDL_MIX', 'ALL', 'ALL', 'WARN', TRUE, NULL, '单工单混合 DDL 与 DML');
-- ---------------------------------------------------------------------
-- 7. 全局审批流（instance_id 为 NULL）
--    LOW 无节点 = 免审；MEDIUM = 主管；HIGH = 主管 → DBA；
--    CRITICAL = 主管 → DBA → 管理员，且一律强制执行前二次确认（由风险等级推导）。
--    实例专用流程由管理员在界面添加，其节点追加在全局链之后（只会更严）。
--    注意：节点按 risk_level + instance_id IS NULL 关联，不按中文流程名匹配。
-- ---------------------------------------------------------------------
INSERT INTO t_flow_rule (name, instance_id, risk_level, enabled, created_by) VALUES
  ('全局 · LOW', NULL, 'LOW', TRUE, NULL),
  ('全局 · MEDIUM', NULL, 'MEDIUM', TRUE, NULL),
  ('全局 · HIGH', NULL, 'HIGH', TRUE, NULL),
  ('全局 · CRITICAL', NULL, 'CRITICAL', TRUE, NULL);
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 1, 'ROLE', 'LEAD', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'MEDIUM';
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 1, 'ROLE', 'LEAD', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'HIGH';
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 2, 'ROLE', 'DBA', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'HIGH';
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 1, 'ROLE', 'LEAD', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'CRITICAL';
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 2, 'ROLE', 'DBA', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'CRITICAL';
INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, mode)
SELECT f.id, 3, 'ROLE', 'ADMIN', 'OR' FROM t_flow_rule f
WHERE f.instance_id IS NULL AND f.risk_level = 'CRITICAL';
