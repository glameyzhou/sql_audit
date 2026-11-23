-- =====================================================================
-- SQL 审核平台 · 平台库 DDL（PostgreSQL 16 方言，Flyway V1 —— 只含 DDL）
--
-- 本文件为 db/migration/mysql/V1__schema.sql 的 PostgreSQL 逐句翻译：
--   1. ENGINE=InnoDB / DEFAULT CHARSET 删除；表/列 COMMENT 改为表外
--      COMMENT ON TABLE/COLUMN ... IS '...'（逐条补齐，不删注释）。
--   2. AUTO_INCREMENT -> BIGSERIAL；DATETIME(3) -> TIMESTAMP(3)。
--   3. TINYINT(1) -> BOOLEAN；LONGBLOB -> BYTEA；TEXT -> TEXT。
--      哈希/指纹列两方言统一 VARCHAR(64)（PG 的 CHAR(n) 会右补空格）。
--   4. 无 ON UPDATE CURRENT_TIMESTAMP（PG 无此语法）：updated_at 一律由 DAO
--      显式写，MySQL 版也刻意不用 ON UPDATE，两方言行为逐条一致。
--   5. 表名沿用 t_ 前缀（t_user/t_role 非保留字，无需双引号）。
--   6. 内联 KEY -> 表外 CREATE INDEX；UNIQUE KEY -> 表内 UNIQUE 约束。
--      PG 索引名在 schema 内全局唯一，故索引名一律带表名前缀。
--   7. 逻辑外键（不建物理 FOREIGN KEY），CHECK 约束保留（布尔字面量用 FALSE/TRUE）。
--   8. 种子数据全部在 V2__seed.sql（本文件只建结构）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 账号与 RBAC（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE t_user (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  email         VARCHAR(128),
  source        VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',
  password_hash VARCHAR(255),
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  last_login_at TIMESTAMP(3),
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (username)
);

COMMENT ON TABLE t_user IS '账号（无删除功能，停用即可） 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_user.username IS '登录名';
COMMENT ON COLUMN t_user.display_name IS '显示名';
COMMENT ON COLUMN t_user.source IS 'LOCAL 本地账号 / WEWORK 企微（UserSource）';
COMMENT ON COLUMN t_user.password_hash IS 'bcrypt 哈希；source=LOCAL 时非空';
COMMENT ON COLUMN t_user.status IS 'ACTIVE/DISABLED（UserStatus）；每请求实时校验，停用即刻失效';
COMMENT ON COLUMN t_user.last_login_at IS '上次登录时间';

CREATE TABLE t_role (
  id             BIGSERIAL PRIMARY KEY,
  code           VARCHAR(32)  NOT NULL,
  name           VARCHAR(32)  NOT NULL,
  description    VARCHAR(255),
  builtin        BOOLEAN      NOT NULL DEFAULT FALSE,
  instance_scope VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (code)
);

COMMENT ON TABLE t_role IS '角色 唯一写入方：sqlaudit-server；超管 = 持有全部权限点的普通角色，代码无特判';
COMMENT ON COLUMN t_role.code IS '角色码，纯数据（代码不引用字面量）';
COMMENT ON COLUMN t_role.builtin IS '内置角色：不可删除、code 不可改（不参与任何鉴权判定）';
COMMENT ON COLUMN t_role.instance_scope IS 'ALL 全部实例 / SELECTED 仅 t_role_instance 授权的实例（InstanceScope）';

CREATE TABLE t_user_role (
  user_id    BIGINT       NOT NULL,
  role_id    BIGINT       NOT NULL,
  granted_by BIGINT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE t_user_role IS '用户-角色关联 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_user_role.granted_by IS '授权人（user_id），配合审计链追溯';

CREATE INDEX idx_user_role_role ON t_user_role (role_id);

CREATE TABLE t_permission (
  id          BIGSERIAL PRIMARY KEY,
  code        VARCHAR(64)  NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  module      VARCHAR(32)  NOT NULL,
  description VARCHAR(255),
  sort        INT          NOT NULL DEFAULT 0,
  all_users   BOOLEAN      NOT NULL DEFAULT FALSE,
  meta        BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (code)
);

COMMENT ON TABLE t_permission IS '权限点目录（由 V2 种子维护，界面只读） 唯一写入方：DML 种子';
COMMENT ON COLUMN t_permission.code IS '权限点 code：供 DML 种子引用，代码不引用';
COMMENT ON COLUMN t_permission.name IS '界面显示名';
COMMENT ON COLUMN t_permission.module IS '界面分组名（中文）';
COMMENT ON COLUMN t_permission.sort IS '界面排序';
COMMENT ON COLUMN t_permission.all_users IS '所有登录用户默认拥有（不写 t_role_permission 授权行）';
COMMENT ON COLUMN t_permission.meta IS '元权限：防自锁不变式的对象（至少一个 ACTIVE 用户须持有）';

CREATE TABLE t_permission_api (
  id            BIGSERIAL PRIMARY KEY,
  permission_id BIGINT       NOT NULL,
  http_method   VARCHAR(8)   NOT NULL,
  url_pattern   VARCHAR(255) NOT NULL,
  UNIQUE (permission_id, http_method, url_pattern)
);

COMMENT ON TABLE t_permission_api IS '权限点-接口绑定（1:N；同一接口可绑多个权限点，持有任一即放行） 唯一写入方：DML 种子';
COMMENT ON COLUMN t_permission_api.http_method IS 'GET/POST/PUT/DELETE/PATCH；HEAD 查表前折叠为 GET';
COMMENT ON COLUMN t_permission_api.url_pattern IS 'Spring 处理器模式原文（如 /api/tickets/{id}），不是请求原始 URI';

CREATE INDEX idx_permission_api_route ON t_permission_api (http_method, url_pattern);

CREATE TABLE t_role_permission (
  role_id       BIGINT       NOT NULL,
  permission_id BIGINT       NOT NULL,
  granted_by    BIGINT,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, permission_id)
);

COMMENT ON TABLE t_role_permission IS '角色-权限点授权（功能权限轴） 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_role_permission.granted_by IS '授权人（user_id）';

CREATE INDEX idx_role_permission_permission ON t_role_permission (permission_id);

CREATE TABLE t_role_instance (
  role_id     BIGINT       NOT NULL,
  instance_id BIGINT       NOT NULL,
  granted_by  BIGINT,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, instance_id)
);

COMMENT ON TABLE t_role_instance IS '角色-实例授权（数据范围轴；仅 t_role.instance_scope=SELECTED 时参与判定） 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_role_instance.granted_by IS '授权人（user_id）';

CREATE INDEX idx_role_instance_instance ON t_role_instance (instance_id);

-- ---------------------------------------------------------------------
-- 2. 实例与凭据（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE t_instance (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(128) NOT NULL,
  dialect        VARCHAR(16)  NOT NULL,
  owner_id       BIGINT       NOT NULL,
  host           VARCHAR(255) NOT NULL,
  port           INT          NOT NULL,
  default_schema VARCHAR(128),
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by     BIGINT       NOT NULL,
  created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at     TIMESTAMP(3)
);

COMMENT ON TABLE t_instance IS '生产实例 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_instance.name IS '实例名；服务层校验「未删除实例中唯一」';
COMMENT ON COLUMN t_instance.dialect IS 'MYSQL/PG/IOTDB（Dialect）';
COMMENT ON COLUMN t_instance.owner_id IS '实例负责人（必须是有效用户）；审批链 INSTANCE_OWNER 节点解析到本人';
COMMENT ON COLUMN t_instance.default_schema IS '默认库；IoTDB 存 storage group 路径';
COMMENT ON COLUMN t_instance.status IS 'ACTIVE/DISABLED（InstanceStatus）';
COMMENT ON COLUMN t_instance.deleted_at IS '软删；列表与查询只返回 NULL 的行';
COMMENT ON COLUMN t_instance.created_by IS '创建人（user_id）';

CREATE TABLE t_credential (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     BIGINT       NOT NULL,
  account_type    VARCHAR(16)  NOT NULL,
  username        VARCHAR(128) NOT NULL,
  password_enc    VARCHAR(512) NOT NULL,
  last_rotated_at TIMESTAMP(3),
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (instance_id, account_type)
);

COMMENT ON TABLE t_credential IS '加密凭据 唯一写入方：sqlaudit-server；查看凭据须写审计，绝不回显明文';
COMMENT ON COLUMN t_credential.account_type IS 'RO_META/RO_DATA/RW_EXEC 三类生产账号物理隔离（AccountType）';
COMMENT ON COLUMN t_credential.password_enc IS 'CredentialCrypto 产出的文本 ivBase64.ciphertextBase64（本来就是文本，不用二进制列）';
COMMENT ON COLUMN t_credential.last_rotated_at IS '上次轮换时间';

-- ---------------------------------------------------------------------
-- 3. 审核规则与审批流配置
-- ---------------------------------------------------------------------

CREATE TABLE t_rule (
  id             BIGSERIAL PRIMARY KEY,
  code           VARCHAR(64)  NOT NULL,
  dialect        VARCHAR(16)  NOT NULL,
  statement_type VARCHAR(16)  NOT NULL,
  severity       VARCHAR(8)   NOT NULL,
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  params         TEXT,
  description    VARCHAR(255) NOT NULL,
  created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (code)
);

COMMENT ON TABLE t_rule IS '审核规则 唯一写入方：sqlaudit-server；运行时只取 enabled / severity 两项';
COMMENT ON COLUMN t_rule.code IS 'R_NO_WHERE 等（RuleCode）';
COMMENT ON COLUMN t_rule.dialect IS 'MYSQL/PG/IOTDB/ALL（RuleDialect，ALL=不限）';
COMMENT ON COLUMN t_rule.statement_type IS 'SELECT/INSERT/UPDATE/DELETE/DDL/ALL（RuleStatementType，ALL=不限）';
COMMENT ON COLUMN t_rule.severity IS 'ERROR 阻断 / WARN 提示（RuleSeverity）';
COMMENT ON COLUMN t_rule.params IS '阈值等 JSON 文本（原样读写，不用方言 JSON 类型）';
COMMENT ON COLUMN t_rule.enabled IS '是否启用（关闭则运行时跳过该规则）';

CREATE TABLE t_flow_rule (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL,
  instance_id BIGINT,
  risk_level  VARCHAR(16)  NOT NULL,
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by  BIGINT,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 实例专用流程唯一（MySQL 侧约束名 uk_flow_scope_risk）；全局流程
  -- （instance_id 为 NULL）的唯一性由服务层校验（MySQL/PG 的 UNIQUE 都不约束
  -- NULL），违反返回 DUPLICATE_FLOW
  UNIQUE (instance_id, risk_level)
);

COMMENT ON TABLE t_flow_rule IS '审批流程（范围 × 风险等级） 唯一写入方：sqlaudit-server；变更须写审计';
COMMENT ON COLUMN t_flow_rule.instance_id IS 'NULL=全局流程；非空=该实例专用流程（节点追加在全局链之后，只会更严）';
COMMENT ON COLUMN t_flow_rule.risk_level IS 'LOW/MEDIUM/HIGH/CRITICAL（RiskLevel）';
COMMENT ON COLUMN t_flow_rule.enabled IS '是否启用（关闭则其节点不参与路由）';
COMMENT ON COLUMN t_flow_rule.created_by IS '创建人（user_id）；V2 种子流程为 NULL';

CREATE TABLE t_flow_node (
  id            BIGSERIAL PRIMARY KEY,
  flow_rule_id  BIGINT       NOT NULL,
  node_order    INT          NOT NULL,
  approver_type VARCHAR(16)  NOT NULL,
  approver_ref  VARCHAR(64)  NOT NULL,
  mode          VARCHAR(8)   NOT NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_flow_node IS '审批节点 唯一写入方：sqlaudit-server（整体替换某流程的节点）';
COMMENT ON COLUMN t_flow_node.node_order IS '组序，从 1 连续；同值为一组';
COMMENT ON COLUMN t_flow_node.approver_type IS 'USER/ROLE/INSTANCE_OWNER（ApproverType）';
COMMENT ON COLUMN t_flow_node.approver_ref IS 'USER=user_id；ROLE=角色 code；INSTANCE_OWNER=*';
COMMENT ON COLUMN t_flow_node.mode IS 'AND 会签 / OR 或签（ApprovalMode）；同组必须一致';

CREATE INDEX idx_flow_node_flow ON t_flow_node (flow_rule_id, node_order);

-- ---------------------------------------------------------------------
-- 4. 工单与执行单元
--    写入边界（状态机）：创建与审批态 → sqlaudit-workflow；
--    执行态（leased/executing/success/failed/needs_confirm）→ sqlaudit-executor。
-- ---------------------------------------------------------------------

CREATE TABLE t_ticket (
  id                   BIGSERIAL PRIMARY KEY,
  title                VARCHAR(255) NOT NULL,
  creator_id           BIGINT       NOT NULL,
  instance_id          BIGINT       NOT NULL,
  risk_level           VARCHAR(16)  NOT NULL,
  status               VARCHAR(24)  NOT NULL,
  rollback_summary     TEXT,
  sql_text             BYTEA,
  shard_config         TEXT,
  needs_second_confirm BOOLEAN      NOT NULL DEFAULT FALSE,
  confirmed_by         BIGINT,
  confirmed_at         TIMESTAMP(3),
  trace_id             VARCHAR(64)  NOT NULL,
  created_at           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_ticket IS '工单 创建/审批态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';
COMMENT ON COLUMN t_ticket.instance_id IS '目标实例（单实例工单）';
COMMENT ON COLUMN t_ticket.risk_level IS 'LOW/MEDIUM/HIGH/CRITICAL（RiskLevel）';
COMMENT ON COLUMN t_ticket.status IS '小写状态码（TicketStatus）';
COMMENT ON COLUMN t_ticket.rollback_summary IS '提交预览时的可回滚清单（JSON 文本，原样读写）';
COMMENT ON COLUMN t_ticket.sql_text IS '工单提交的原始 SQL（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）';
COMMENT ON COLUMN t_ticket.shard_config IS '分表执行配置（JSON 文本：template/suffixes）';
COMMENT ON COLUMN t_ticket.needs_second_confirm IS '是否需要执行前二次确认（CRITICAL 链）';
COMMENT ON COLUMN t_ticket.confirmed_by IS '执行前二次确认人（user_id）';
COMMENT ON COLUMN t_ticket.confirmed_at IS '执行前二次确认时间';
COMMENT ON COLUMN t_ticket.trace_id IS '请求链路 trace id（贯穿工单 / 执行 / 审计）';
COMMENT ON COLUMN t_ticket.updated_at IS '状态迁移时间，审批时效 approval_ttl 的基准';

CREATE INDEX idx_ticket_creator ON t_ticket (creator_id, created_at);
CREATE INDEX idx_ticket_status ON t_ticket (status);
CREATE INDEX idx_ticket_instance ON t_ticket (instance_id);

CREATE TABLE t_ticket_unit (
  id              BIGSERIAL PRIMARY KEY,
  ticket_id       BIGINT        NOT NULL,
  instance_id     BIGINT        NOT NULL,
  schema_name     VARCHAR(128),
  object_name     VARCHAR(255),
  unit_order      INT           NOT NULL,
  statement       BYTEA         NOT NULL,
  statement_hash  VARCHAR(64)   NOT NULL,
  statement_type  VARCHAR(16)   NOT NULL,
  status          VARCHAR(24)   NOT NULL,
  lease_owner     VARCHAR(64),
  lease_until     TIMESTAMP(3),
  heartbeat_at    TIMESTAMP(3),
  intent_written  BOOLEAN       NOT NULL DEFAULT FALSE,
  executed_at     TIMESTAMP(3),
  affected_rows   BIGINT,
  ddl_fingerprint VARCHAR(64),
  error_message   VARCHAR(1024),
  created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 不变式：intent_written=TRUE 只能出现在 executing/success/failed/needs_confirm；
  --        status='executing' 必然 intent_written=TRUE
  CONSTRAINT chk_unit_intent CHECK (
    (intent_written = FALSE OR status IN ('executing','success','failed','needs_confirm'))
    AND (status <> 'executing' OR intent_written = TRUE)
  )
);

COMMENT ON TABLE t_ticket_unit IS '执行单元 创建态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';
COMMENT ON COLUMN t_ticket_unit.schema_name IS 'IoTDB 存 group 路径';
COMMENT ON COLUMN t_ticket_unit.object_name IS '对象（表）名；锁粒度与指纹比对用';
COMMENT ON COLUMN t_ticket_unit.unit_order IS '语句在工单内的执行序号';
COMMENT ON COLUMN t_ticket_unit.statement IS '单条执行语句（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）';
COMMENT ON COLUMN t_ticket_unit.statement_hash IS '明文的 SHA-256 hex（不是密文的）';
COMMENT ON COLUMN t_ticket_unit.statement_type IS 'SELECT/INSERT/UPDATE/DELETE/DDL/OTHER（StatementType）';
COMMENT ON COLUMN t_ticket_unit.status IS '小写状态码（UnitStatus）';
COMMENT ON COLUMN t_ticket_unit.lease_owner IS 'worker 实例 id';
COMMENT ON COLUMN t_ticket_unit.lease_until IS '租约到期时间；过期且未心跳可被其他 worker 抢占';
COMMENT ON COLUMN t_ticket_unit.heartbeat_at IS 'worker 心跳时间；reaper 按超时回收 executing 单元';
COMMENT ON COLUMN t_ticket_unit.intent_written IS '已预写执行意图（SQL 可能已到生产库，禁止自动重试）';
COMMENT ON COLUMN t_ticket_unit.executed_at IS '无唯一约束：同毫秒完成是正常情况';
COMMENT ON COLUMN t_ticket_unit.ddl_fingerprint IS '审核时的表结构指纹，执行前复核（R_SCHEMA_DRIFT 门禁）';

CREATE INDEX idx_unit_ticket_order ON t_ticket_unit (ticket_id, unit_order);
-- worker 抢占主路径：WHERE intent_written=FALSE AND (status=? OR (status=? AND lease_until<?))
CREATE INDEX idx_unit_claim ON t_ticket_unit (intent_written, status, lease_until);
-- reaper：WHERE status='executing' AND heartbeat_at<? AND executed_at IS NULL
CREATE INDEX idx_unit_heartbeat ON t_ticket_unit (status, heartbeat_at);

-- ---------------------------------------------------------------------
-- 5. 回滚 SQL（唯一写入方：sqlaudit-rollback）
-- ---------------------------------------------------------------------

CREATE TABLE t_rollback_sql (
  id                 BIGSERIAL PRIMARY KEY,
  unit_id            BIGINT       NOT NULL,
  stmt_count         INT          NOT NULL DEFAULT 0,
  total_bytes        BIGINT       NOT NULL DEFAULT 0,
  generator          VARCHAR(32)  NOT NULL,
  status             VARCHAR(16)  NOT NULL,
  coverage           VARCHAR(16)  NOT NULL,
  unavailable_reason VARCHAR(255),
  preimage_hash      VARCHAR(64),
  expires_at         TIMESTAMP(3),
  created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (unit_id)
);

COMMENT ON TABLE t_rollback_sql IS '回滚主记录 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_rollback_sql.stmt_count IS '反向语句条数（明细在 t_rollback_statement）';
COMMENT ON COLUMN t_rollback_sql.total_bytes IS '反向语句密文总字节数（估算回放规模）';
COMMENT ON COLUMN t_rollback_sql.generator IS 'MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT/NONE（RollbackGenerator）';
COMMENT ON COLUMN t_rollback_sql.status IS 'generated/verified/applied/expired/not_available/failed/partial（RollbackStatus）';
COMMENT ON COLUMN t_rollback_sql.coverage IS 'FULL/PARTIAL/NONE（RollbackCoverage）';
COMMENT ON COLUMN t_rollback_sql.preimage_hash IS '前镜像指纹，用于依赖检测';
COMMENT ON COLUMN t_rollback_sql.expires_at IS '前镜像 / 反向语句有效期，过期后不可回滚';

CREATE TABLE t_rollback_statement (
  id           BIGSERIAL PRIMARY KEY,
  rollback_id  BIGINT       NOT NULL,
  stmt_order   INT          NOT NULL,
  reverse_sql  BYTEA        NOT NULL,
  value_params BYTEA,
  applied_at   TIMESTAMP(3),
  UNIQUE (rollback_id, stmt_order)
);

COMMENT ON TABLE t_rollback_statement IS '反向语句明细 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_rollback_statement.stmt_order IS '回放顺序（与原行顺序无关，但必须稳定）';
COMMENT ON COLUMN t_rollback_statement.reverse_sql IS '带 ? 占位符的反向语句（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）';
COMMENT ON COLUMN t_rollback_statement.value_params IS '该条语句的旧值参数 JSON（前镜像生产数据，AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）';
COMMENT ON COLUMN t_rollback_statement.applied_at IS '逐条幂等回放';

-- ---------------------------------------------------------------------
-- 6. 审批记录（唯一写入方：sqlaudit-workflow）
--    指派对象（approver_type + approver_ref）与实际决策人（decided_by）分列存储。
-- ---------------------------------------------------------------------

CREATE TABLE t_approval (
  id            BIGSERIAL PRIMARY KEY,
  ticket_id     BIGINT        NOT NULL,
  flow_node_id  BIGINT        NOT NULL,
  node_order    INT           NOT NULL,
  mode          VARCHAR(8)    NOT NULL,
  approver_type VARCHAR(16)   NOT NULL,
  approver_ref  VARCHAR(64)   NOT NULL,
  result        VARCHAR(16)   NOT NULL,
  decided_by    BIGINT,
  comment       VARCHAR(1024),
  decided_at    TIMESTAMP(3),
  created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 防并发重复生成；其前缀 (ticket_id, node_order) 同时服务 listByTicket，无需再建一条索引
  UNIQUE (ticket_id, node_order, approver_type, approver_ref)
);

COMMENT ON TABLE t_approval IS '审批记录 唯一写入方：sqlaudit-workflow（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_approval.flow_node_id IS '生成本记录的 t_flow_node.id（审批配置可追溯）';
COMMENT ON COLUMN t_approval.node_order IS '生效链中的组序（全局组在前、实例组在后，重新编号）';
COMMENT ON COLUMN t_approval.mode IS 'AND 会签 / OR 或签（ApprovalMode）';
COMMENT ON COLUMN t_approval.approver_type IS 'USER/ROLE/INSTANCE_OWNER（ApproverType）';
COMMENT ON COLUMN t_approval.approver_ref IS 'USER=user_id；ROLE=角色 code；INSTANCE_OWNER=提交时解析出的负责人 user_id（快照）';
COMMENT ON COLUMN t_approval.result IS 'PENDING/APPROVED/REJECTED/TRANSFERRED/SKIPPED（ApprovalResult）；SKIPPED=节点解析到提交人本人，视为通过';
COMMENT ON COLUMN t_approval.decided_by IS '实际决策人（认领 ROLE 节点、决策、转交、跳过时写入）';
COMMENT ON COLUMN t_approval.comment IS '审批意见';
COMMENT ON COLUMN t_approval.decided_at IS '决策时间（决策 / 认领 / 转交 / 跳过时写入）';

CREATE INDEX idx_approval_pending ON t_approval (result, created_at);

-- ---------------------------------------------------------------------
-- 7. 执行锁（唯一写入方：sqlaudit-executor，reaper 清理同模块）
-- ---------------------------------------------------------------------

CREATE TABLE t_exec_lock (
  lock_key    VARCHAR(255) PRIMARY KEY,
  unit_id     BIGINT       NOT NULL,
  worker_id   VARCHAR(64)  NOT NULL,
  acquired_at TIMESTAMP(3) NOT NULL,
  expires_at  TIMESTAMP(3) NOT NULL
);

COMMENT ON TABLE t_exec_lock IS '执行锁（并发互斥门禁） 唯一写入方：sqlaudit-executor';
COMMENT ON COLUMN t_exec_lock.lock_key IS 'inst:{id} 或 inst:{id}:obj:{schema.table}';
COMMENT ON COLUMN t_exec_lock.unit_id IS '持锁的执行单元';
COMMENT ON COLUMN t_exec_lock.worker_id IS '持锁 worker 实例 id';
COMMENT ON COLUMN t_exec_lock.expires_at IS '与租约同生命周期，过期由 reaper 清理';

CREATE INDEX idx_exec_lock_expires ON t_exec_lock (expires_at);

-- ---------------------------------------------------------------------
-- 8. 审计日志与 hash 链（唯一写入方：sqlaudit-audit，单写入点）
-- ---------------------------------------------------------------------

CREATE TABLE t_chain_head (
  id         SMALLINT     PRIMARY KEY DEFAULT 1,
  last_seq   BIGINT       NOT NULL DEFAULT 0,
  last_hash  VARCHAR(64)  NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_chain_head_single CHECK (id = 1)
);

COMMENT ON TABLE t_chain_head IS '审计链头（单写入点，行锁串行） 唯一写入方：sqlaudit-audit';
COMMENT ON COLUMN t_chain_head.id IS '单行表，恒为 1';
COMMENT ON COLUMN t_chain_head.last_seq IS '已分配的最后一个链序号';
COMMENT ON COLUMN t_chain_head.last_hash IS '最新一条审计记录的 hash（创世块为 64 个 0）';

CREATE TABLE t_audit_log (
  id          BIGSERIAL PRIMARY KEY,
  seq         BIGINT        NOT NULL,
  actor_id    BIGINT        NOT NULL,
  actor_name  VARCHAR(64)   NOT NULL,
  actor_role  VARCHAR(32)   NOT NULL,
  action      VARCHAR(64)   NOT NULL,
  target_type VARCHAR(32)   NOT NULL,
  target_id   VARCHAR(64),
  instance_id BIGINT,
  detail      BYTEA,
  result      VARCHAR(16)   NOT NULL,
  client_ip   VARCHAR(64),
  user_agent  VARCHAR(256),
  trace_id    VARCHAR(64)   NOT NULL,
  prev_hash   VARCHAR(64)   NOT NULL,
  hash        VARCHAR(64)   NOT NULL,
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (seq)
);

COMMENT ON TABLE t_audit_log IS '全量审计（append-only + hash 链，禁逻辑删除） 唯一写入方：sqlaudit-audit';
COMMENT ON COLUMN t_audit_log.id IS '物理主键，不参与 hash 链';
COMMENT ON COLUMN t_audit_log.seq IS '链序号，由 chain_head 行锁内分配';
COMMENT ON COLUMN t_audit_log.actor_role IS '操作人首个角色 code；系统动作写 SYSTEM';
COMMENT ON COLUMN t_audit_log.action IS 'LOGIN/QUERY_EXECUTED/TICKET_STATE_CHANGE/...（AuditAction）';
COMMENT ON COLUMN t_audit_log.target_type IS '审计对象类型（AuditTargetType）';
COMMENT ON COLUMN t_audit_log.instance_id IS '关联实例（无则 NULL）';
COMMENT ON COLUMN t_audit_log.detail IS 'AES-GCM 密文（摘要 + SQL 全文）；哈希算明文，密钥轮换不破链';
COMMENT ON COLUMN t_audit_log.result IS 'SUCCESS/REJECTED/FAILED/PARTIAL（AuditResult）';
COMMENT ON COLUMN t_audit_log.client_ip IS 'X-Forwarded-For 首段，缺省 remoteAddr';
COMMENT ON COLUMN t_audit_log.user_agent IS '请求 User-Agent';
COMMENT ON COLUMN t_audit_log.trace_id IS '请求链路 trace id';
COMMENT ON COLUMN t_audit_log.prev_hash IS '前一条记录的 hash（append-only 防篡改链）';
COMMENT ON COLUMN t_audit_log.hash IS 'SHA256(prev_hash || seq || canonical_json(明文记录))';
