-- =====================================================================
-- SQL 审核平台 · 平台库 DDL（Flyway V1）
-- 依据：sql-audit-platform-design-v2.md v2.1 §3/§4/§5/§6/§7/§8/§9
--
-- 约定：
--   1. 全库统一「逻辑外键」，不建物理 FOREIGN KEY（大表/分布式实践）。
--   2. 每张表 COMMENT 标注「唯一写入方」；有多写方的表标注状态机边界。
--   3. 审计相关表（audit_log/query_log/chain_head/audit_result/export_task/
--      rollback_sql/rollback_statement/approval/ticket/ticket_unit/exec_lock/
--      worker_heartbeat）禁止逻辑删除 deleted_at。
--   4. 加密列（AES-GCM）长度 = (明文 + 12B nonce + 16B tag) × 1.33(Base64)。
--      例：明文 6KB → 密文约 8KB，故 statement 用 VARBINARY(8192)。
--   5. 权限模型由 DDL + GRANT 显式表达：应用账号对审计相关表只有
--      INSERT/SELECT，UPDATE/DELETE 在库级回收（部署脚本另行 GRANT）。
--
-- 【待确认项】(v2.1 未给出完整 DDL 的表，字段按文档语义补齐，已用
--   「[推断]」标注；上线前需与架构评审对齐)：
--   a. user/role/user_role/instance/credential/flow_rule/flow_node/
--      audit_result/masking_rule/export_task/worker_heartbeat 共 11 张表。
--   b. §3.2 的「回滚工单 rollback_ticket」状态机未在 §9 建表——
--      本版复用 ticket + approval，回滚生命周期由 rollback_sql.status 表达，
--      是否需要独立 rollback_ticket 表待决策。
--   c. §9 末尾「所有表带 deleted_at」与具体 DDL（ticket/ticket_unit 等
--      无 deleted_at）不一致——本版以具体 DDL 为准，仅对补齐的 11 张业务表
--      加 deleted_at。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 账号与权限（唯一写入方：sqlaudit-auth）
-- ---------------------------------------------------------------------

CREATE TABLE `t_user` (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  username       VARCHAR(64)   NOT NULL COMMENT '登录名（企微账号/本地应急账号）',
  display_name   VARCHAR(64)   NOT NULL COMMENT '显示名',
  email          VARCHAR(128)  NULL,
  source         VARCHAR(16)   NOT NULL DEFAULT 'WEWORK' COMMENT 'WEWORK 企微 / LOCAL 本地应急',
  wework_openid  VARCHAR(128)  NULL COMMENT '企微 userid/openid；source=WEWORK 时非空',
  password_hash  VARCHAR(255)  NULL COMMENT '本地应急账号 bcrypt 哈希；source=LOCAL 时非空',
  totp_secret    VARCHAR(128)  NULL COMMENT '本地账号 TOTP 密钥（加密）',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED；特权操作每次实时校验',
  last_login_at  DATETIME(3)   NULL,
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3)   NULL,
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_wework_openid (wework_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号 [推断] 唯一写入方：sqlaudit-auth';

CREATE TABLE `t_role` (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(32)   NOT NULL COMMENT 'DEV/LEAD/DBA/ADMIN',
  name        VARCHAR(32)   NOT NULL,
  description VARCHAR(255)  NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色 [推断] 唯一写入方：sqlaudit-auth';

CREATE TABLE t_user_role (
  user_id    BIGINT       NOT NULL,
  role_id    BIGINT       NOT NULL,
  granted_by BIGINT       NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role_id),
  KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联 [推断] 唯一写入方：sqlaudit-auth';

-- ---------------------------------------------------------------------
-- 2. 实例与凭据（凭据唯一写入方：sqlaudit-auth；实例唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE t_instance (
  id             BIGINT        PRIMARY KEY AUTO_INCREMENT,
  name           VARCHAR(128)  NOT NULL,
  dialect        VARCHAR(16)   NOT NULL COMMENT 'MYSQL/PG/IOTDB',
  environment    VARCHAR(16)   NOT NULL COMMENT 'prod/staging',
  owner_id       BIGINT        NOT NULL COMMENT '实例 Owner，自动进入该实例高危审批链',
  host           VARCHAR(255)  NOT NULL,
  port           INT           NOT NULL,
  default_schema VARCHAR(128)  NULL COMMENT '默认库；IoTDB 存 storage group 路径',
  replica_dsn    VARCHAR(512)  NULL COMMENT '只读副本 DSN；空则按 §4.4 降级策略（主库 COUNT 或标影响面未知）',
  credential_id  BIGINT        NULL COMMENT '指向 credential 组；三类账号见 credential.account_type [推断]',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  created_by     BIGINT        NOT NULL,
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3)   NULL,
  KEY idx_owner (owner_id),
  KEY idx_env (environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实例 [推断] 唯一写入方：sqlaudit-server';

CREATE TABLE t_credential (
  id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
  instance_id     BIGINT        NOT NULL,
  account_type    VARCHAR(16)   NOT NULL COMMENT 'RO_META/RO_DATA/RW_EXEC 三类生产账号物理隔离',
  username        VARCHAR(128)  NOT NULL,
  password_enc    VARBINARY(512) NOT NULL COMMENT 'AES-GCM 密文 = 明文+12B nonce+16B tag，Base64 放大 1.33 倍',
  key_version     INT           NOT NULL DEFAULT 1 COMMENT '主密钥版本，轮换用',
  last_rotated_at DATETIME(3)   NULL,
  expires_at      DATETIME(3)   NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inst_type (instance_id, account_type),
  KEY idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密凭据 [推断] 唯一写入方：sqlaudit-auth；查看凭据须写审计，绝不回显明文';

-- ---------------------------------------------------------------------
-- 3. 规则与规则组（唯一写入方：sqlaudit-rule）
-- ---------------------------------------------------------------------

CREATE TABLE t_rule (
  id             BIGINT        PRIMARY KEY AUTO_INCREMENT,
  code           VARCHAR(64)   NOT NULL COMMENT 'R_MYSQL_NO_WHERE 等',
  dialect        VARCHAR(16)   NOT NULL COMMENT 'MYSQL/PG/IOTDB/ALL',
  statement_type VARCHAR(16)   NOT NULL COMMENT 'INSERT/UPDATE/DELETE/DDL/SELECT/ALL',
  severity       VARCHAR(8)    NOT NULL COMMENT 'ERROR 阻断 / WARN 提示',
  enabled        TINYINT(1)    NOT NULL DEFAULT 1,
  params         JSON          NULL COMMENT '阈值等',
  description    VARCHAR(255)  NOT NULL,
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核规则 唯一写入方：sqlaudit-rule；豁免须走审批并写审计';

CREATE TABLE t_rule_group (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL,
  environment VARCHAR(16)  NOT NULL COMMENT 'prod/staging',
  created_by  BIGINT       NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则组 唯一写入方：sqlaudit-rule';

CREATE TABLE t_rule_group_binding (
  group_id    BIGINT NOT NULL,
  instance_id BIGINT NOT NULL,
  PRIMARY KEY (group_id, instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则组-实例绑定 唯一写入方：sqlaudit-rule';

-- ---------------------------------------------------------------------
-- 4. 审批流配置（唯一写入方：sqlaudit-workflow）
-- ---------------------------------------------------------------------

CREATE TABLE t_flow_rule (
  id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
  name            VARCHAR(64)  NOT NULL,
  environment     VARCHAR(16)  NOT NULL COMMENT 'prod/staging/ALL',
  instance_id     BIGINT       NULL COMMENT 'NULL=全局默认 L1；非空=实例级覆盖 L2（只能加严）',
  risk_level      VARCHAR(16)  NOT NULL COMMENT 'LOW/MEDIUM/HIGH/CRITICAL，命中即用此审批链',
  non_overridable TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'CRITICAL 不可被任何配置降级',
  priority        INT          NOT NULL DEFAULT 0 COMMENT '同 risk_level 多条时取 priority 最高',
  enabled         TINYINT(1)   NOT NULL DEFAULT 1,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_route (environment, instance_id, risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批流规则 [推断] 唯一写入方：sqlaudit-workflow；变更须写审计';

CREATE TABLE t_flow_node (
  id              BIGINT      PRIMARY KEY AUTO_INCREMENT,
  flow_rule_id    BIGINT      NOT NULL,
  node_order      INT         NOT NULL COMMENT '节点顺序；同 node_order 多行 = 会签/或签组',
  approver_type   VARCHAR(16) NOT NULL COMMENT 'USER/ROLE/INSTANCE_OWNER',
  approver_ref    VARCHAR(64) NOT NULL COMMENT 'USER=user_id；ROLE=role code；INSTANCE_OWNER=*',
  mode            VARCHAR(8)  NOT NULL COMMENT 'AND 会签 / OR 或签',
  timeout_minutes INT         NOT NULL DEFAULT 1440 COMMENT '超时企微提醒',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_flow (flow_rule_id, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批节点 [推断] 唯一写入方：sqlaudit-workflow';

-- ---------------------------------------------------------------------
-- 5. 工单与执行单元
--    写入边界（状态机，见 §3.2/§3.3）：
--      创建与审批态（draft/submitted/auditing/approved/...）→ sqlaudit-workflow
--      执行态（leased/executing/success/failed/needs_confirm）→ sqlaudit-executor
--      两者通过状态机边界隔离，同一字段不双写。
-- ---------------------------------------------------------------------

CREATE TABLE t_ticket (
  id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
  title           VARCHAR(255) NOT NULL,
  creator_id      BIGINT       NOT NULL,
  instance_ids    JSON         NOT NULL,
  environment     VARCHAR(16)  NOT NULL,
  risk_level      VARCHAR(16)  NOT NULL,
  status          VARCHAR(24)  NOT NULL,
  rollback_summary JSON        NULL COMMENT '提交预览时的可回滚清单',
  scheduled_at    DATETIME(3)  NULL COMMENT '低峰执行窗口',
  trace_id        VARCHAR(64)  NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_creator (creator_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单 创建/审批态写方：sqlaudit-workflow';

CREATE TABLE t_ticket_unit (
  id              BIGINT         PRIMARY KEY AUTO_INCREMENT,
  ticket_id       BIGINT         NOT NULL,
  instance_id     BIGINT         NOT NULL,
  schema_name     VARCHAR(128)   NULL COMMENT 'IoTDB 存 group 路径',
  object_name     VARCHAR(255)   NULL,
  unit_order      INT            NOT NULL,
  statement       VARBINARY(8192) NOT NULL COMMENT '加密',
  statement_hash  CHAR(64)       NOT NULL,
  statement_type  VARCHAR(16)    NOT NULL,
  dialect_specific JSON          NULL COMMENT 'IoTDB 等方言扩展',
  status          VARCHAR(24)    NOT NULL,
  lease_owner     VARCHAR(64)    NULL,
  lease_until     DATETIME(3)    NULL,
  heartbeat_at    DATETIME(3)    NULL,
  intent_written  TINYINT(1)     NOT NULL DEFAULT 0,
  executed_at     DATETIME(3)    NULL COMMENT '无唯一约束：同毫秒完成是正常情况',
  exec_fingerprint CHAR(64)      NULL COMMENT '跨工单重复提交检测，非幂等手段',
  preimage_rows   BIGINT         NULL COMMENT '与 affected_rows 对账',
  affected_rows   BIGINT         NULL,
  ddl_fingerprint CHAR(64)       NULL COMMENT '审核时的表结构指纹，执行前复核（§3.5）',
  binlog_start    VARCHAR(128)   NULL,
  binlog_end      VARCHAR(128)   NULL,
  error_message   VARCHAR(1024)  NULL,
  created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_fingerprint (exec_fingerprint, created_at),
  KEY idx_ticket_order (ticket_id, unit_order),
  KEY idx_lease (status, lease_until, intent_written),
  -- §3.3 不变式：intent_written=1 只能出现在 executing/success/failed/needs_confirm；
  --             status='executing' 必然 intent_written=1
  CONSTRAINT chk_unit_intent CHECK (
    (intent_written = 0 OR status IN ('executing','success','failed','needs_confirm'))
    AND (status <> 'executing' OR intent_written = 1)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行单元 创建态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';

-- ---------------------------------------------------------------------
-- 6. 规则命中与影响面评估（唯一写入方：sqlaudit-rule）
-- ---------------------------------------------------------------------

CREATE TABLE t_audit_result (
  id         BIGINT        PRIMARY KEY AUTO_INCREMENT,
  ticket_id  BIGINT        NOT NULL,
  unit_id    BIGINT        NOT NULL,
  rule_code  VARCHAR(64)   NOT NULL,
  level      VARCHAR(8)    NOT NULL COMMENT 'ERROR/WARN',
  message    VARCHAR(1024) NULL,
  extra      JSON          NULL COMMENT '阈值、命中的列/对象等',
  created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_unit (unit_id),
  KEY idx_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则命中结果 [推断] 唯一写入方：sqlaudit-rule（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 7. 回滚 SQL（唯一写入方：sqlaudit-rollback）
-- ---------------------------------------------------------------------

CREATE TABLE t_rollback_sql (
  id                BIGINT        PRIMARY KEY AUTO_INCREMENT,
  unit_id           BIGINT        NOT NULL,
  stmt_count        INT           NOT NULL DEFAULT 0 COMMENT '反向语句条数（明细在 rollback_statement）',
  total_bytes       BIGINT        NOT NULL DEFAULT 0,
  generator         VARCHAR(32)   NOT NULL COMMENT 'MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT',
  status            VARCHAR(16)   NOT NULL COMMENT 'generated/verified/applied/expired/not_available',
  coverage          VARCHAR(16)   NOT NULL COMMENT 'FULL/PARTIAL/NONE',
  unavailable_reason VARCHAR(255)  NULL,
  preimage_hash     CHAR(64)      NULL COMMENT '前镜像指纹，用于依赖检测',
  binlog_start      VARCHAR(128)  NULL,
  binlog_end        VARCHAR(128)  NULL,
  snapshot_uri      VARCHAR(512)  NULL COMMENT '影子表/IoTDB 快照位置',
  expires_at        DATETIME(3)   NULL,
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_unit (unit_id),
  KEY idx_status (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回滚主记录 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';

CREATE TABLE t_rollback_statement (
  id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  rollback_id  BIGINT       NOT NULL,
  stmt_order   INT          NOT NULL COMMENT '回放顺序（与原行顺序无关，但必须稳定）',
  reverse_sql  BLOB         NOT NULL COMMENT '加密；带 ? 占位符的参数化语句',
  value_params BLOB         NULL COMMENT '加密；该条语句的旧值参数（CBOR/JSON）',
  applied_at   DATETIME(3)  NULL COMMENT '逐条幂等回放',
  UNIQUE KEY uk_rb_order (rollback_id, stmt_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反向语句明细 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 8. 审批记录（唯一写入方：sqlaudit-workflow）
-- ---------------------------------------------------------------------

CREATE TABLE t_approval (
  id          BIGINT         PRIMARY KEY AUTO_INCREMENT,
  ticket_id   BIGINT         NOT NULL,
  node_id     BIGINT         NOT NULL COMMENT '关联 flow_node',
  node_order  INT            NOT NULL,
  approver_id BIGINT         NOT NULL,
  mode        VARCHAR(8)     NOT NULL COMMENT 'AND 会签 / OR 或签',
  result      VARCHAR(16)    NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TRANSFERRED',
  comment     VARCHAR(1024)  NULL,
  decided_at  DATETIME(3)    NULL,
  created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_ticket (ticket_id, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录 唯一写入方：sqlaudit-workflow（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 9. 执行锁（唯一写入方：sqlaudit-executor，reaper 清理同模块）
-- ---------------------------------------------------------------------

CREATE TABLE t_exec_lock (
  lock_key    VARCHAR(255) PRIMARY KEY COMMENT 'inst:{id} 或 inst:{id}:obj:{schema.table}',
  unit_id     BIGINT       NOT NULL,
  worker_id   VARCHAR(64)  NOT NULL,
  acquired_at DATETIME(3)  NOT NULL,
  expires_at  DATETIME(3)  NOT NULL COMMENT '与租约同生命周期，过期由 reaper 清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行锁（§3.5 并发互斥） 唯一写入方：sqlaudit-executor';

-- ---------------------------------------------------------------------
-- 10. 审计日志与 hash 链（唯一写入方：sqlaudit-audit，单写入点）
-- ---------------------------------------------------------------------

CREATE TABLE t_chain_head (
  id         TINYINT     PRIMARY KEY DEFAULT 1 COMMENT '单行表，恒为 1',
  last_seq   BIGINT      NOT NULL DEFAULT 0,
  last_hash  CHAR(64)    NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计链头（§7.3 单写入点，行锁串行） 唯一写入方：sqlaudit-audit';

CREATE TABLE t_audit_log (
  id           BIGINT         PRIMARY KEY AUTO_INCREMENT COMMENT '物理主键，不参与 hash 链',
  seq          BIGINT         NOT NULL COMMENT '链序号，由 chain_head 行锁内分配（见 §7.3）',
  actor_id     BIGINT         NOT NULL,
  actor_name   VARCHAR(64)    NOT NULL,
  actor_role   VARCHAR(32)    NOT NULL,
  action       VARCHAR(64)    NOT NULL COMMENT 'LOGIN/QUERY_EXECUTED/TICKET_STATE_CHANGE/UNIT_EXEC/ROLLBACK_EXECUTE/...',
  target_type  VARCHAR(32)    NOT NULL,
  target_id    VARCHAR(64)    NULL,
  instance_id  BIGINT         NULL,
  detail       VARBINARY(8192) NULL COMMENT '加密；摘要 + SQL 全文（如有）',
  result       VARCHAR(16)    NOT NULL COMMENT 'SUCCESS/REJECTED/FAILED/PARTIAL',
  client_ip    VARCHAR(64)    NULL COMMENT '取可信 X-Forwarded-For 链',
  user_agent   VARCHAR(256)   NULL,
  trace_id     VARCHAR(64)    NOT NULL,
  prev_hash    CHAR(64)       NOT NULL,
  hash         CHAR(64)       NOT NULL,
  created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_seq (seq),
  KEY idx_actor_time (actor_id, created_at),
  KEY idx_action_time (action, created_at),
  KEY idx_target (target_type, target_id),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量审计（append-only + hash 链，禁逻辑删除） 唯一写入方：sqlaudit-audit';

-- ---------------------------------------------------------------------
-- 11. 查询审计与掩码（唯一写入方：sqlaudit-query）
-- ---------------------------------------------------------------------

CREATE TABLE t_query_log (
  id          BIGINT         PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT         NOT NULL,
  instance_id BIGINT         NOT NULL,
  schema_name VARCHAR(128)   NULL,
  sql_text    VARBINARY(8192) NOT NULL COMMENT '加密',
  sql_hash    CHAR(64)       NOT NULL,
  row_count   INT            NULL,
  elapsed_ms  INT            NULL,
  truncated   TINYINT(1)     NOT NULL DEFAULT 0,
  masked      TINYINT(1)     NOT NULL DEFAULT 0,
  client_ip   VARCHAR(64)    NULL,
  trace_id    VARCHAR(64)    NOT NULL,
  created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_inst_time (instance_id, created_at),
  KEY idx_hash (sql_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='查询审计（按月分表 query_log_YYYYMM，禁逻辑删除） 唯一写入方：sqlaudit-query';

CREATE TABLE t_masking_rule (
  id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
  instance_id BIGINT        NOT NULL,
  schema_name VARCHAR(128)  NOT NULL,
  table_name  VARCHAR(255)  NOT NULL,
  column_name VARCHAR(255)  NOT NULL,
  mask_type   VARCHAR(16)   NOT NULL COMMENT 'ID_CARD/PHONE/EMAIL/REGEX',
  regex       VARCHAR(512)  NULL COMMENT 'mask_type=REGEX 时的自定义正则',
  created_by  BIGINT        NOT NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)   NULL,
  KEY idx_inst_table (instance_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='掩码规则 [推断] 唯一写入方：sqlaudit-query；变更须写审计';

CREATE TABLE t_export_task (
  id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT        NOT NULL,
  instance_id BIGINT        NOT NULL,
  query_ref   VARCHAR(64)   NULL COMMENT '关联 query_log.trace_id',
  file_uri    VARCHAR(512)  NULL COMMENT '导出文件对象存储位置',
  status      VARCHAR(16)   NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/READY/EXPIRED',
  watermark   VARCHAR(255)  NOT NULL COMMENT '操作人/时间/trace_id 水印',
  expires_at  DATETIME(3)   NULL COMMENT '限时下载',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user (user_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出任务 [推断] 唯一写入方：sqlaudit-query（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 12. worker 心跳（唯一写入方：sqlaudit-worker）
-- ---------------------------------------------------------------------

CREATE TABLE t_worker_heartbeat (
  id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
  worker_id        VARCHAR(64)  NOT NULL COMMENT 'worker 实例 ID，对应 ticket_unit.lease_owner',
  host             VARCHAR(255) NULL,
  ip               VARCHAR(64)  NULL,
  started_at       DATETIME(3)  NOT NULL,
  last_heartbeat_at DATETIME(3) NOT NULL,
  lease_count      INT          NOT NULL DEFAULT 0 COMMENT '当前持有租约数',
  status           VARCHAR(16)  NOT NULL DEFAULT 'ALIVE' COMMENT 'ALIVE/DEAD',
  UNIQUE KEY uk_worker (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='worker 存活与租约观测 [推断] 唯一写入方：sqlaudit-worker';

-- =====================================================================
-- 初始化数据
-- =====================================================================

-- hash 链创世块：last_hash 与 §7.3 定义一致（64 个 '0'）
INSERT INTO t_chain_head (id, last_seq, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- 内置角色（§8.2 权限矩阵）
INSERT INTO `t_role` (code, name, description) VALUES
  ('DEV',   '开发',   '提交工单、查询通道'),
  ('LEAD',  '主管',   '审批（非本人）'),
  ('DBA',   'DBA',    '执行/二次确认、规则与流程配置'),
  ('ADMIN', '管理员', '凭据管理、审计检索导出、全量配置');
