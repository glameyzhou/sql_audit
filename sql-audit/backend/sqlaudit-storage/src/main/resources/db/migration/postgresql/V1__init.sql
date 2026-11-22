-- =====================================================================
-- SQL 审核平台 · 平台库 DDL（PostgreSQL 方言，Flyway V1）
-- 依据：sql-audit-platform-design-v2.md v2.1 §3/§4/§5/§6/§7/§8/§9
--
-- 本文件为 MySQL 版 db/migration/mysql/V1__init.sql 的 PostgreSQL 逐句翻译：
--   1. ENGINE=InnoDB / DEFAULT CHARSET 删除；表/列 COMMENT 改为表外
--      COMMENT ON TABLE/COLUMN ... IS '...' 语法（逐条补齐，不删注释）。
--   2. AUTO_INCREMENT -> BIGSERIAL；DATETIME(3) -> TIMESTAMP(3)。
--   3. TINYINT(1) -> BOOLEAN；VARBINARY/BLOB -> BYTEA；JSON -> JSONB。
--      CHAR(64) -> VARCHAR(64)：PG 的 CHAR(n) 会右补空格（getString 读到补齐后的
--      定长串，MySQL CHAR 读时去尾随空格），hash/指纹列改用 VARCHAR 保持读语义一致。
--   4. ON UPDATE CURRENT_TIMESTAMP(3) 删除（PG 无此语法，updated_at 由应用层显式写）。
--   5. 表名沿用 t_ 前缀（t_user/t_role 非保留字，无需双引号）。
--   6. 内联 KEY -> 表外 CREATE INDEX；UNIQUE KEY -> 表内 UNIQUE 约束。
--   7. PG 索引名为 schema 内全局唯一（不同于 MySQL 每表独立），同名索引
--      idx_status / idx_ticket 已改为表名前缀消歧。
--   8. 逻辑外键（不建物理 FOREIGN KEY），CHECK 约束保留（布尔字面量用 FALSE/TRUE）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 账号与权限（唯一写入方：sqlaudit-auth）
-- ---------------------------------------------------------------------

CREATE TABLE t_user (
  id             BIGSERIAL PRIMARY KEY,
  username       VARCHAR(64)   NOT NULL,
  display_name   VARCHAR(64)   NOT NULL,
  email          VARCHAR(128),
  source         VARCHAR(16)   NOT NULL DEFAULT 'WEWORK',
  wework_openid  VARCHAR(128),
  password_hash  VARCHAR(255),
  totp_secret    VARCHAR(128),
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  last_login_at  TIMESTAMP(3),
  created_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at     TIMESTAMP(3),
  UNIQUE (username),
  UNIQUE (wework_openid)
);

COMMENT ON TABLE t_user IS '账号 [推断] 唯一写入方：sqlaudit-auth';
COMMENT ON COLUMN t_user.username IS '登录名（企微账号/本地应急账号）';
COMMENT ON COLUMN t_user.display_name IS '显示名';
COMMENT ON COLUMN t_user.source IS 'WEWORK 企微 / LOCAL 本地应急';
COMMENT ON COLUMN t_user.wework_openid IS '企微 userid/openid；source=WEWORK 时非空';
COMMENT ON COLUMN t_user.password_hash IS '本地应急账号 bcrypt 哈希；source=LOCAL 时非空';
COMMENT ON COLUMN t_user.totp_secret IS '本地账号 TOTP 密钥（加密）';
COMMENT ON COLUMN t_user.status IS 'ACTIVE/DISABLED；特权操作每次实时校验';

CREATE TABLE t_role (
  id          BIGSERIAL PRIMARY KEY,
  code        VARCHAR(32)   NOT NULL,
  name        VARCHAR(32)   NOT NULL,
  description VARCHAR(255),
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (code)
);

COMMENT ON TABLE t_role IS '角色 [推断] 唯一写入方：sqlaudit-auth';
COMMENT ON COLUMN t_role.code IS 'DEV/LEAD/DBA/ADMIN';

CREATE TABLE t_user_role (
  user_id    BIGINT       NOT NULL,
  role_id    BIGINT       NOT NULL,
  granted_by BIGINT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE t_user_role IS '用户-角色关联 [推断] 唯一写入方：sqlaudit-auth';

CREATE INDEX idx_role ON t_user_role (role_id);

-- ---------------------------------------------------------------------
-- 2. 实例与凭据（凭据唯一写入方：sqlaudit-auth；实例唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE t_instance (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(128) NOT NULL,
  dialect        VARCHAR(16)  NOT NULL,
  environment    VARCHAR(16)  NOT NULL,
  owner_id       BIGINT       NOT NULL,
  host           VARCHAR(255) NOT NULL,
  port           INT          NOT NULL,
  default_schema VARCHAR(128),
  replica_dsn    VARCHAR(512),
  credential_id  BIGINT,
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by     BIGINT       NOT NULL,
  created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at     TIMESTAMP(3)
);

COMMENT ON TABLE t_instance IS '实例 [推断] 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_instance.dialect IS 'MYSQL/PG/IOTDB';
COMMENT ON COLUMN t_instance.environment IS 'prod/staging';
COMMENT ON COLUMN t_instance.owner_id IS '实例 Owner，自动进入该实例高危审批链';
COMMENT ON COLUMN t_instance.default_schema IS '默认库；IoTDB 存 storage group 路径';
COMMENT ON COLUMN t_instance.replica_dsn IS '只读副本 DSN；空则按 §4.4 降级策略（主库 COUNT 或标影响面未知）';
COMMENT ON COLUMN t_instance.credential_id IS '指向 credential 组；三类账号见 credential.account_type [推断]';

CREATE INDEX idx_owner ON t_instance (owner_id);
CREATE INDEX idx_env ON t_instance (environment);

CREATE TABLE t_credential (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     BIGINT        NOT NULL,
  account_type    VARCHAR(16)   NOT NULL,
  username        VARCHAR(128)  NOT NULL,
  password_enc    BYTEA         NOT NULL,
  key_version     INT           NOT NULL DEFAULT 1,
  last_rotated_at TIMESTAMP(3),
  expires_at      TIMESTAMP(3),
  created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (instance_id, account_type)
);

COMMENT ON TABLE t_credential IS '加密凭据 [推断] 唯一写入方：sqlaudit-auth；查看凭据须写审计，绝不回显明文';
COMMENT ON COLUMN t_credential.account_type IS 'RO_META/RO_DATA/RW_EXEC 三类生产账号物理隔离';
COMMENT ON COLUMN t_credential.password_enc IS 'AES-GCM 密文 = 明文+12B nonce+16B tag，Base64 放大 1.33 倍';
COMMENT ON COLUMN t_credential.key_version IS '主密钥版本，轮换用';

CREATE INDEX idx_expires ON t_credential (expires_at);

-- ---------------------------------------------------------------------
-- 3. 规则与规则组（唯一写入方：sqlaudit-rule）
-- ---------------------------------------------------------------------

CREATE TABLE t_rule (
  id             BIGSERIAL PRIMARY KEY,
  code           VARCHAR(64)   NOT NULL,
  dialect        VARCHAR(16)   NOT NULL,
  statement_type VARCHAR(16)   NOT NULL,
  severity       VARCHAR(8)    NOT NULL,
  enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
  params         JSONB,
  description    VARCHAR(255)  NOT NULL,
  created_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (code)
);

COMMENT ON TABLE t_rule IS '审核规则 唯一写入方：sqlaudit-rule；豁免须走审批并写审计';
COMMENT ON COLUMN t_rule.code IS 'R_MYSQL_NO_WHERE 等';
COMMENT ON COLUMN t_rule.dialect IS 'MYSQL/PG/IOTDB/ALL';
COMMENT ON COLUMN t_rule.statement_type IS 'INSERT/UPDATE/DELETE/DDL/SELECT/ALL';
COMMENT ON COLUMN t_rule.severity IS 'ERROR 阻断 / WARN 提示';
COMMENT ON COLUMN t_rule.params IS '阈值等';

CREATE TABLE t_rule_group (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL,
  environment VARCHAR(16)  NOT NULL,
  created_by  BIGINT       NOT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_rule_group IS '规则组 唯一写入方：sqlaudit-rule';
COMMENT ON COLUMN t_rule_group.environment IS 'prod/staging';

CREATE TABLE t_rule_group_binding (
  group_id    BIGINT NOT NULL,
  instance_id BIGINT NOT NULL,
  PRIMARY KEY (group_id, instance_id)
);

COMMENT ON TABLE t_rule_group_binding IS '规则组-实例绑定 唯一写入方：sqlaudit-rule';

-- ---------------------------------------------------------------------
-- 4. 审批流配置（唯一写入方：sqlaudit-workflow）
-- ---------------------------------------------------------------------

CREATE TABLE t_flow_rule (
  id              BIGSERIAL PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL,
  environment     VARCHAR(16)  NOT NULL,
  instance_id     BIGINT,
  risk_level      VARCHAR(16)  NOT NULL,
  non_overridable BOOLEAN      NOT NULL DEFAULT FALSE,
  priority        INT          NOT NULL DEFAULT 0,
  enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by      BIGINT       NOT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_flow_rule IS '审批流规则 [推断] 唯一写入方：sqlaudit-workflow；变更须写审计';
COMMENT ON COLUMN t_flow_rule.environment IS 'prod/staging/ALL';
COMMENT ON COLUMN t_flow_rule.instance_id IS 'NULL=全局默认 L1；非空=实例级覆盖 L2（只能加严）';
COMMENT ON COLUMN t_flow_rule.risk_level IS 'LOW/MEDIUM/HIGH/CRITICAL，命中即用此审批链';
COMMENT ON COLUMN t_flow_rule.non_overridable IS 'CRITICAL 不可被任何配置降级';
COMMENT ON COLUMN t_flow_rule.priority IS '同 risk_level 多条时取 priority 最高';

CREATE INDEX idx_route ON t_flow_rule (environment, instance_id, risk_level);

CREATE TABLE t_flow_node (
  id              BIGSERIAL PRIMARY KEY,
  flow_rule_id    BIGINT       NOT NULL,
  node_order      INT          NOT NULL,
  approver_type   VARCHAR(16)  NOT NULL,
  approver_ref    VARCHAR(64)  NOT NULL,
  mode            VARCHAR(8)   NOT NULL,
  timeout_minutes INT          NOT NULL DEFAULT 1440,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_flow_node IS '审批节点 [推断] 唯一写入方：sqlaudit-workflow';
COMMENT ON COLUMN t_flow_node.node_order IS '节点顺序；同 node_order 多行 = 会签/或签组';
COMMENT ON COLUMN t_flow_node.approver_type IS 'USER/ROLE/INSTANCE_OWNER';
COMMENT ON COLUMN t_flow_node.approver_ref IS 'USER=user_id；ROLE=role code；INSTANCE_OWNER=*';
COMMENT ON COLUMN t_flow_node.mode IS 'AND 会签 / OR 或签';
COMMENT ON COLUMN t_flow_node.timeout_minutes IS '超时企微提醒';

CREATE INDEX idx_flow ON t_flow_node (flow_rule_id, node_order);

-- ---------------------------------------------------------------------
-- 5. 工单与执行单元
-- ---------------------------------------------------------------------

CREATE TABLE t_ticket (
  id              BIGSERIAL PRIMARY KEY,
  title           VARCHAR(255) NOT NULL,
  creator_id      BIGINT       NOT NULL,
  instance_ids    JSONB        NOT NULL,
  environment     VARCHAR(16)  NOT NULL,
  risk_level      VARCHAR(16)  NOT NULL,
  status          VARCHAR(24)  NOT NULL,
  rollback_summary JSONB,
  scheduled_at    TIMESTAMP(3),
  trace_id        VARCHAR(64)  NOT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_ticket IS '工单 创建/审批态写方：sqlaudit-workflow';
COMMENT ON COLUMN t_ticket.rollback_summary IS '提交预览时的可回滚清单';
COMMENT ON COLUMN t_ticket.scheduled_at IS '低峰执行窗口';

CREATE INDEX idx_creator ON t_ticket (creator_id, created_at);
CREATE INDEX idx_ticket_status ON t_ticket (status);

CREATE TABLE t_ticket_unit (
  id              BIGSERIAL PRIMARY KEY,
  ticket_id       BIGINT         NOT NULL,
  instance_id     BIGINT         NOT NULL,
  schema_name     VARCHAR(128),
  object_name     VARCHAR(255),
  unit_order      INT            NOT NULL,
  statement       BYTEA          NOT NULL,
  statement_hash  VARCHAR(64)    NOT NULL,
  statement_type  VARCHAR(16)    NOT NULL,
  dialect_specific JSONB,
  status          VARCHAR(24)    NOT NULL,
  lease_owner     VARCHAR(64),
  lease_until     TIMESTAMP(3),
  heartbeat_at    TIMESTAMP(3),
  intent_written  BOOLEAN        NOT NULL DEFAULT FALSE,
  executed_at     TIMESTAMP(3),
  exec_fingerprint VARCHAR(64),
  preimage_rows   BIGINT,
  affected_rows   BIGINT,
  ddl_fingerprint VARCHAR(64),
  binlog_start    VARCHAR(128),
  binlog_end      VARCHAR(128),
  error_message   VARCHAR(1024),
  created_at      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- §3.3 不变式：intent_written=true 只能出现在 executing/success/failed/needs_confirm；
  --             status='executing' 必然 intent_written=true
  CONSTRAINT chk_unit_intent CHECK (
    (intent_written = FALSE OR status IN ('executing','success','failed','needs_confirm'))
    AND (status <> 'executing' OR intent_written = TRUE)
  )
);

COMMENT ON TABLE t_ticket_unit IS '执行单元 创建态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';
COMMENT ON COLUMN t_ticket_unit.schema_name IS 'IoTDB 存 group 路径';
COMMENT ON COLUMN t_ticket_unit.statement IS '加密';
COMMENT ON COLUMN t_ticket_unit.dialect_specific IS 'IoTDB 等方言扩展';
COMMENT ON COLUMN t_ticket_unit.executed_at IS '无唯一约束：同毫秒完成是正常情况';
COMMENT ON COLUMN t_ticket_unit.exec_fingerprint IS '跨工单重复提交检测，非幂等手段';
COMMENT ON COLUMN t_ticket_unit.preimage_rows IS '与 affected_rows 对账';
COMMENT ON COLUMN t_ticket_unit.ddl_fingerprint IS '审核时的表结构指纹，执行前复核（§3.5）';

CREATE INDEX idx_fingerprint ON t_ticket_unit (exec_fingerprint, created_at);
CREATE INDEX idx_ticket_order ON t_ticket_unit (ticket_id, unit_order);
CREATE INDEX idx_lease ON t_ticket_unit (status, lease_until, intent_written);

-- ---------------------------------------------------------------------
-- 6. 规则命中与影响面评估（唯一写入方：sqlaudit-rule）
-- ---------------------------------------------------------------------

CREATE TABLE t_audit_result (
  id         BIGSERIAL PRIMARY KEY,
  ticket_id  BIGINT        NOT NULL,
  unit_id    BIGINT        NOT NULL,
  rule_code  VARCHAR(64)   NOT NULL,
  level      VARCHAR(8)    NOT NULL,
  message    VARCHAR(1024),
  extra      JSONB,
  created_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_audit_result IS '规则命中结果 [推断] 唯一写入方：sqlaudit-rule（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_audit_result.level IS 'ERROR/WARN';
COMMENT ON COLUMN t_audit_result.extra IS '阈值、命中的列/对象等';

CREATE INDEX idx_unit ON t_audit_result (unit_id);
CREATE INDEX idx_audit_result_ticket ON t_audit_result (ticket_id);

-- ---------------------------------------------------------------------
-- 7. 回滚 SQL（唯一写入方：sqlaudit-rollback）
-- ---------------------------------------------------------------------

CREATE TABLE t_rollback_sql (
  id                BIGSERIAL PRIMARY KEY,
  unit_id           BIGINT        NOT NULL,
  stmt_count        INT           NOT NULL DEFAULT 0,
  total_bytes       BIGINT        NOT NULL DEFAULT 0,
  generator         VARCHAR(32)   NOT NULL,
  status            VARCHAR(16)   NOT NULL,
  coverage          VARCHAR(16)   NOT NULL,
  unavailable_reason VARCHAR(255),
  preimage_hash     VARCHAR(64),
  binlog_start      VARCHAR(128),
  binlog_end        VARCHAR(128),
  snapshot_uri      VARCHAR(512),
  expires_at        TIMESTAMP(3),
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (unit_id)
);

COMMENT ON TABLE t_rollback_sql IS '回滚主记录 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_rollback_sql.stmt_count IS '反向语句条数（明细在 rollback_statement）';
COMMENT ON COLUMN t_rollback_sql.generator IS 'MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT';
COMMENT ON COLUMN t_rollback_sql.status IS 'generated/verified/applied/expired/not_available';
COMMENT ON COLUMN t_rollback_sql.coverage IS 'FULL/PARTIAL/NONE';
COMMENT ON COLUMN t_rollback_sql.preimage_hash IS '前镜像指纹，用于依赖检测';
COMMENT ON COLUMN t_rollback_sql.snapshot_uri IS '影子表/IoTDB 快照位置';

CREATE INDEX idx_rollback_sql_status ON t_rollback_sql (status, expires_at);

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
COMMENT ON COLUMN t_rollback_statement.reverse_sql IS '加密；带 ? 占位符的参数化语句';
COMMENT ON COLUMN t_rollback_statement.value_params IS '加密；该条语句的旧值参数（CBOR/JSON）';
COMMENT ON COLUMN t_rollback_statement.applied_at IS '逐条幂等回放';

-- ---------------------------------------------------------------------
-- 8. 审批记录（唯一写入方：sqlaudit-workflow）
-- ---------------------------------------------------------------------

CREATE TABLE t_approval (
  id          BIGSERIAL PRIMARY KEY,
  ticket_id   BIGINT         NOT NULL,
  node_id     BIGINT         NOT NULL,
  node_order  INT            NOT NULL,
  approver_id BIGINT         NOT NULL,
  mode        VARCHAR(8)     NOT NULL,
  result      VARCHAR(16)    NOT NULL,
  comment     VARCHAR(1024),
  decided_at  TIMESTAMP(3),
  created_at  TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_approval IS '审批记录 唯一写入方：sqlaudit-workflow（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_approval.node_id IS '关联 flow_node';
COMMENT ON COLUMN t_approval.mode IS 'AND 会签 / OR 或签';
COMMENT ON COLUMN t_approval.result IS 'PENDING/APPROVED/REJECTED/TRANSFERRED';

CREATE INDEX idx_approval_ticket ON t_approval (ticket_id, node_order);

-- ---------------------------------------------------------------------
-- 9. 执行锁（唯一写入方：sqlaudit-executor）
-- ---------------------------------------------------------------------

CREATE TABLE t_exec_lock (
  lock_key    VARCHAR(255) PRIMARY KEY,
  unit_id     BIGINT       NOT NULL,
  worker_id   VARCHAR(64)  NOT NULL,
  acquired_at TIMESTAMP(3) NOT NULL,
  expires_at  TIMESTAMP(3) NOT NULL
);

COMMENT ON TABLE t_exec_lock IS '执行锁（§3.5 并发互斥） 唯一写入方：sqlaudit-executor';
COMMENT ON COLUMN t_exec_lock.lock_key IS 'inst:{id} 或 inst:{id}:obj:{schema.table}';
COMMENT ON COLUMN t_exec_lock.expires_at IS '与租约同生命周期，过期由 reaper 清理';

-- ---------------------------------------------------------------------
-- 10. 审计日志与 hash 链（唯一写入方：sqlaudit-audit，单写入点）
-- ---------------------------------------------------------------------

CREATE TABLE t_chain_head (
  id         SMALLINT    PRIMARY KEY DEFAULT 1,
  last_seq   BIGINT      NOT NULL DEFAULT 0,
  last_hash  VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_chain_head IS '审计链头（§7.3 单写入点，行锁串行） 唯一写入方：sqlaudit-audit';
COMMENT ON COLUMN t_chain_head.id IS '单行表，恒为 1';

CREATE TABLE t_audit_log (
  id           BIGSERIAL PRIMARY KEY,
  seq          BIGINT         NOT NULL,
  actor_id     BIGINT         NOT NULL,
  actor_name   VARCHAR(64)    NOT NULL,
  actor_role   VARCHAR(32)    NOT NULL,
  action       VARCHAR(64)    NOT NULL,
  target_type  VARCHAR(32)    NOT NULL,
  target_id    VARCHAR(64),
  instance_id  BIGINT,
  detail       BYTEA,
  result       VARCHAR(16)    NOT NULL,
  client_ip    VARCHAR(64),
  user_agent   VARCHAR(256),
  trace_id     VARCHAR(64)    NOT NULL,
  prev_hash    VARCHAR(64)    NOT NULL,
  hash         VARCHAR(64)    NOT NULL,
  created_at   TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (seq)
);

COMMENT ON TABLE t_audit_log IS '全量审计（append-only + hash 链，禁逻辑删除） 唯一写入方：sqlaudit-audit';
COMMENT ON COLUMN t_audit_log.id IS '物理主键，不参与 hash 链';
COMMENT ON COLUMN t_audit_log.seq IS '链序号，由 chain_head 行锁内分配（见 §7.3）';
COMMENT ON COLUMN t_audit_log.action IS 'LOGIN/TICKET_APPROVE/UNIT_EXEC/...';
COMMENT ON COLUMN t_audit_log.detail IS '加密；摘要 + SQL 全文（如有）';
COMMENT ON COLUMN t_audit_log.result IS 'SUCCESS/FAIL/DENIED';
COMMENT ON COLUMN t_audit_log.client_ip IS '取可信 X-Forwarded-For 链';

CREATE INDEX idx_actor_time ON t_audit_log (actor_id, created_at);
CREATE INDEX idx_action_time ON t_audit_log (action, created_at);
CREATE INDEX idx_target ON t_audit_log (target_type, target_id);
CREATE INDEX idx_trace ON t_audit_log (trace_id);

-- ---------------------------------------------------------------------
-- 11. 查询审计与掩码（唯一写入方：sqlaudit-query）
-- ---------------------------------------------------------------------

CREATE TABLE t_query_log (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT         NOT NULL,
  instance_id BIGINT         NOT NULL,
  schema_name VARCHAR(128),
  sql_text    BYTEA          NOT NULL,
  sql_hash    VARCHAR(64)    NOT NULL,
  row_count   INT,
  elapsed_ms  INT,
  truncated   BOOLEAN        NOT NULL DEFAULT FALSE,
  masked      BOOLEAN        NOT NULL DEFAULT FALSE,
  client_ip   VARCHAR(64),
  trace_id    VARCHAR(64)    NOT NULL,
  created_at  TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_query_log IS '查询审计（按月分表 query_log_YYYYMM，禁逻辑删除） 唯一写入方：sqlaudit-query';
COMMENT ON COLUMN t_query_log.sql_text IS '加密';

CREATE INDEX idx_user_time ON t_query_log (user_id, created_at);
CREATE INDEX idx_inst_time ON t_query_log (instance_id, created_at);
CREATE INDEX idx_hash ON t_query_log (sql_hash);

CREATE TABLE t_masking_rule (
  id          BIGSERIAL PRIMARY KEY,
  instance_id BIGINT        NOT NULL,
  schema_name VARCHAR(128)  NOT NULL,
  table_name  VARCHAR(255)  NOT NULL,
  column_name VARCHAR(255)  NOT NULL,
  mask_type   VARCHAR(16)   NOT NULL,
  regex       VARCHAR(512),
  created_by  BIGINT        NOT NULL,
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP(3)
);

COMMENT ON TABLE t_masking_rule IS '掩码规则 [推断] 唯一写入方：sqlaudit-query；变更须写审计';
COMMENT ON COLUMN t_masking_rule.mask_type IS 'ID_CARD/PHONE/EMAIL/REGEX';
COMMENT ON COLUMN t_masking_rule.regex IS 'mask_type=REGEX 时的自定义正则';

CREATE INDEX idx_inst_table ON t_masking_rule (instance_id, schema_name, table_name);

CREATE TABLE t_export_task (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT        NOT NULL,
  instance_id BIGINT        NOT NULL,
  query_ref   VARCHAR(64),
  file_uri    VARCHAR(512),
  status      VARCHAR(16)   NOT NULL,
  watermark   VARCHAR(255)  NOT NULL,
  expires_at  TIMESTAMP(3),
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_export_task IS '导出任务 [推断] 唯一写入方：sqlaudit-query（审计相关，禁逻辑删除）';
COMMENT ON COLUMN t_export_task.query_ref IS '关联 query_log.trace_id';
COMMENT ON COLUMN t_export_task.file_uri IS '导出文件对象存储位置';
COMMENT ON COLUMN t_export_task.status IS 'PENDING/APPROVED/REJECTED/READY/EXPIRED';
COMMENT ON COLUMN t_export_task.watermark IS '操作人/时间/trace_id 水印';
COMMENT ON COLUMN t_export_task.expires_at IS '限时下载';

CREATE INDEX idx_user ON t_export_task (user_id, created_at);
CREATE INDEX idx_export_task_status ON t_export_task (status);

-- ---------------------------------------------------------------------
-- 12. worker 心跳（唯一写入方：sqlaudit-worker）
-- ---------------------------------------------------------------------

CREATE TABLE t_worker_heartbeat (
  id               BIGSERIAL PRIMARY KEY,
  worker_id        VARCHAR(64)  NOT NULL,
  host             VARCHAR(255),
  ip               VARCHAR(64),
  started_at       TIMESTAMP(3) NOT NULL,
  last_heartbeat_at TIMESTAMP(3) NOT NULL,
  lease_count      INT          NOT NULL DEFAULT 0,
  status           VARCHAR(16)  NOT NULL DEFAULT 'ALIVE',
  UNIQUE (worker_id)
);

COMMENT ON TABLE t_worker_heartbeat IS 'worker 存活与租约观测 [推断] 唯一写入方：sqlaudit-worker';
COMMENT ON COLUMN t_worker_heartbeat.worker_id IS 'worker 实例 ID，对应 ticket_unit.lease_owner';
COMMENT ON COLUMN t_worker_heartbeat.lease_count IS '当前持有租约数';
COMMENT ON COLUMN t_worker_heartbeat.status IS 'ALIVE/DEAD';

-- =====================================================================
-- 初始化数据
-- =====================================================================

-- hash 链创世块：last_hash 与 §7.3 定义一致（64 个 '0'）
INSERT INTO t_chain_head (id, last_seq, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- 内置角色（§8.2 权限矩阵）
INSERT INTO t_role (code, name, description) VALUES
  ('DEV',   '开发',   '提交工单、查询通道'),
  ('LEAD',  '主管',   '审批（非本人）'),
  ('DBA',   'DBA',    '执行/二次确认、规则与流程配置'),
  ('ADMIN', '管理员', '凭据管理、审计检索导出、全量配置');
