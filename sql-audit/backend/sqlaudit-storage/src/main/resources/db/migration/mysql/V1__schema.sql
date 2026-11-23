-- =====================================================================
-- SQL 审核平台 · 平台库 DDL（MySQL 8 方言，Flyway V1 —— 只含 DDL）
--
-- 约定：
--   1. 全库统一「逻辑外键」，不建物理 FOREIGN KEY。
--   2. 每张表 COMMENT 标注唯一写入方；有多写方的表标注状态机边界。
--   3. 审计相关表（audit_log / chain_head / rollback_* / approval / ticket /
--      ticket_unit / exec_lock）禁止逻辑删除。
--   4. SQL 文本列（ticket.sql_text、ticket_unit.statement、
--      rollback_statement.reverse_sql / value_params）存 AES-GCM 密文
--      （iv(12B) || 密文+tag，见 common 的 SqlCipher），库里无明文；
--      statement_hash 按明文计算。密文列用 LONGBLOB：与 PG 的 BYTEA 一样
--      没有 8KB 上限，长度上限由应用层统一校验（单工单 1 MiB / 单查询 64 KiB），
--      避免「MySQL 报 1406、PG 静默成功」的跨方言分叉。
--   5. updated_at 一律由 DAO 显式写（不用 ON UPDATE CURRENT_TIMESTAMP），
--      与 PG 行为逐条一致。
--   6. 功能权限（t_permission + t_permission_api + t_role_permission）与
--      实例数据范围（t_role.instance_scope + t_role_instance）是正交两轴，
--      都过才放行；角色码 / 权限码是纯数据，代码里不做字面量判断。
--   7. 种子数据全部在 V2__seed.sql（本文件只建结构）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 账号与 RBAC（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE `t_user` (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  username       VARCHAR(64)   NOT NULL COMMENT '登录名',
  display_name   VARCHAR(64)   NOT NULL COMMENT '显示名',
  email          VARCHAR(128)  NULL,
  source         VARCHAR(16)   NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL 本地账号 / WEWORK 企微（UserSource）',
  password_hash  VARCHAR(255)  NULL COMMENT 'bcrypt 哈希；source=LOCAL 时非空',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED（UserStatus）；每请求实时校验，停用即刻失效',
  last_login_at  DATETIME(3)   NULL COMMENT '上次登录时间',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号（无删除功能，停用即可） 唯一写入方：sqlaudit-server';

CREATE TABLE `t_role` (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  code           VARCHAR(32)  NOT NULL COMMENT '角色码，纯数据（代码不引用字面量）',
  name           VARCHAR(32)  NOT NULL,
  description    VARCHAR(255) NULL,
  builtin        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '内置角色：不可删除、code 不可改（不参与任何鉴权判定）',
  instance_scope VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL 全部实例 / SELECTED 仅 t_role_instance 授权的实例（InstanceScope）',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色 唯一写入方：sqlaudit-server；超管 = 持有全部权限点的普通角色，代码无特判';

CREATE TABLE t_user_role (
  user_id    BIGINT       NOT NULL,
  role_id    BIGINT       NOT NULL,
  granted_by BIGINT       NULL COMMENT '授权人（user_id），配合审计链追溯',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role_id),
  KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联 唯一写入方：sqlaudit-server';

CREATE TABLE `t_permission` (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(64)  NOT NULL COMMENT '权限点 code：供 DML 种子引用，代码不引用',
  name        VARCHAR(64)  NOT NULL COMMENT '界面显示名',
  module      VARCHAR(32)  NOT NULL COMMENT '界面分组名（中文）',
  description VARCHAR(255) NULL,
  sort        INT          NOT NULL DEFAULT 0 COMMENT '界面排序',
  all_users   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '所有登录用户默认拥有（不写 t_role_permission 授权行）',
  meta        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '元权限：防自锁不变式的对象（至少一个 ACTIVE 用户须持有）',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点目录（由 V2 种子维护，界面只读） 唯一写入方：DML 种子';

CREATE TABLE t_permission_api (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  permission_id BIGINT       NOT NULL,
  http_method   VARCHAR(8)   NOT NULL COMMENT 'GET/POST/PUT/DELETE/PATCH；HEAD 查表前折叠为 GET',
  url_pattern   VARCHAR(255) NOT NULL COMMENT 'Spring 处理器模式原文（如 /api/tickets/{id}），不是请求原始 URI',
  UNIQUE KEY uk_permission_api (permission_id, http_method, url_pattern),
  KEY idx_permission_api_route (http_method, url_pattern)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点-接口绑定（1:N；同一接口可绑多个权限点，持有任一即放行） 唯一写入方：DML 种子';

CREATE TABLE t_role_permission (
  role_id       BIGINT      NOT NULL,
  permission_id BIGINT      NOT NULL,
  granted_by    BIGINT      NULL COMMENT '授权人（user_id）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, permission_id),
  KEY idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限点授权（功能权限轴） 唯一写入方：sqlaudit-server';

CREATE TABLE t_role_instance (
  role_id     BIGINT      NOT NULL,
  instance_id BIGINT      NOT NULL,
  granted_by  BIGINT      NULL COMMENT '授权人（user_id）',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, instance_id),
  KEY idx_role_instance_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-实例授权（数据范围轴；仅 t_role.instance_scope=SELECTED 时参与判定） 唯一写入方：sqlaudit-server';

-- ---------------------------------------------------------------------
-- 2. 实例与凭据（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------

CREATE TABLE t_instance (
  id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
  name           VARCHAR(128) NOT NULL COMMENT '实例名；服务层校验「未删除实例中唯一」',
  dialect        VARCHAR(16)  NOT NULL COMMENT 'MYSQL/PG/IOTDB（Dialect）',
  owner_id       BIGINT       NOT NULL COMMENT '实例负责人（必须是有效用户）；审批链 INSTANCE_OWNER 节点解析到本人',
  host           VARCHAR(255) NOT NULL,
  port           INT          NOT NULL,
  default_schema VARCHAR(128) NULL COMMENT '默认库；IoTDB 存 storage group 路径',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED（InstanceStatus）',
  created_by     BIGINT       NOT NULL COMMENT '创建人（user_id）',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3)  NULL COMMENT '软删；列表与查询只返回 NULL 的行'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产实例 唯一写入方：sqlaudit-server';

CREATE TABLE t_credential (
  id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
  instance_id     BIGINT        NOT NULL,
  account_type    VARCHAR(16)   NOT NULL COMMENT 'RO_META/RO_DATA/RW_EXEC 三类生产账号物理隔离（AccountType）',
  username        VARCHAR(128)  NOT NULL,
  password_enc    VARCHAR(512)  NOT NULL COMMENT 'CredentialCrypto 产出的文本 ivBase64.ciphertextBase64（本来就是文本，不用二进制列）',
  last_rotated_at DATETIME(3)   NULL COMMENT '上次轮换时间',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_credential_inst_type (instance_id, account_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密凭据 唯一写入方：sqlaudit-server；查看凭据须写审计，绝不回显明文';

-- ---------------------------------------------------------------------
-- 3. 审核规则与审批流配置
-- ---------------------------------------------------------------------

CREATE TABLE t_rule (
  id             BIGINT        PRIMARY KEY AUTO_INCREMENT,
  code           VARCHAR(64)   NOT NULL COMMENT 'R_NO_WHERE 等（RuleCode）',
  dialect        VARCHAR(16)   NOT NULL COMMENT 'MYSQL/PG/IOTDB/ALL（RuleDialect，ALL=不限）',
  statement_type VARCHAR(16)   NOT NULL COMMENT 'SELECT/INSERT/UPDATE/DELETE/DDL/ALL（RuleStatementType，ALL=不限）',
  severity       VARCHAR(8)    NOT NULL COMMENT 'ERROR 阻断 / WARN 提示（RuleSeverity）',
  enabled        TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用（关闭则运行时跳过该规则）',
  params         TEXT          NULL COMMENT '阈值等 JSON 文本（原样读写，不用方言 JSON 类型）',
  description    VARCHAR(255)  NOT NULL,
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核规则 唯一写入方：sqlaudit-server；运行时只取 enabled / severity 两项';

CREATE TABLE t_flow_rule (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL,
  instance_id BIGINT       NULL COMMENT 'NULL=全局流程；非空=该实例专用流程（节点追加在全局链之后，只会更严）',
  risk_level  VARCHAR(16)  NOT NULL COMMENT 'LOW/MEDIUM/HIGH/CRITICAL（RiskLevel）',
  enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用（关闭则其节点不参与路由）',
  created_by  BIGINT       NULL COMMENT '创建人（user_id）；V2 种子流程为 NULL',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- uk_flow_scope_risk：实例专用流程唯一；全局流程（instance_id 为 NULL）的
  -- 唯一性由服务层校验（MySQL/PG 的 UNIQUE 都不约束 NULL），违反返回 DUPLICATE_FLOW
  UNIQUE KEY uk_flow_scope_risk (instance_id, risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批流程（范围 × 风险等级） 唯一写入方：sqlaudit-server；变更须写审计';

CREATE TABLE t_flow_node (
  id            BIGINT      PRIMARY KEY AUTO_INCREMENT,
  flow_rule_id  BIGINT      NOT NULL,
  node_order    INT         NOT NULL COMMENT '组序，从 1 连续；同值为一组',
  approver_type VARCHAR(16) NOT NULL COMMENT 'USER/ROLE/INSTANCE_OWNER（ApproverType）',
  approver_ref  VARCHAR(64) NOT NULL COMMENT 'USER=user_id；ROLE=角色 code；INSTANCE_OWNER=*',
  mode          VARCHAR(8)  NOT NULL COMMENT 'AND 会签 / OR 或签（ApprovalMode）；同组必须一致',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_flow_node_flow (flow_rule_id, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批节点 唯一写入方：sqlaudit-server（整体替换某流程的节点）';

-- ---------------------------------------------------------------------
-- 4. 工单与执行单元
--    写入边界（状态机）：创建与审批态 → sqlaudit-workflow；
--    执行态（leased/executing/success/failed/needs_confirm）→ sqlaudit-executor。
-- ---------------------------------------------------------------------

CREATE TABLE t_ticket (
  id                   BIGINT       PRIMARY KEY AUTO_INCREMENT,
  title                VARCHAR(255) NOT NULL,
  creator_id           BIGINT       NOT NULL,
  instance_id          BIGINT       NOT NULL COMMENT '目标实例（单实例工单）',
  risk_level           VARCHAR(16)  NOT NULL COMMENT 'LOW/MEDIUM/HIGH/CRITICAL（RiskLevel）',
  status               VARCHAR(24)  NOT NULL COMMENT '小写状态码（TicketStatus）',
  rollback_summary     TEXT         NULL COMMENT '提交预览时的可回滚清单（JSON 文本，原样读写）',
  sql_text             LONGBLOB     NULL COMMENT '工单提交的原始 SQL（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）',
  shard_config         TEXT         NULL COMMENT '分表执行配置（JSON 文本：template/suffixes）',
  needs_second_confirm TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否需要执行前二次确认（CRITICAL 链）',
  confirmed_by         BIGINT       NULL COMMENT '执行前二次确认人（user_id）',
  confirmed_at         DATETIME(3)  NULL COMMENT '执行前二次确认时间',
  trace_id             VARCHAR(64)  NOT NULL COMMENT '请求链路 trace id（贯穿工单 / 执行 / 审计）',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '状态迁移时间，审批时效 approval_ttl 的基准',
  KEY idx_ticket_creator (creator_id, created_at),
  KEY idx_ticket_status (status),
  KEY idx_ticket_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单 创建/审批态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';

CREATE TABLE t_ticket_unit (
  id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
  ticket_id       BIGINT        NOT NULL,
  instance_id     BIGINT        NOT NULL,
  schema_name     VARCHAR(128)  NULL COMMENT 'IoTDB 存 group 路径',
  object_name     VARCHAR(255)  NULL COMMENT '对象（表）名；锁粒度与指纹比对用',
  unit_order      INT           NOT NULL COMMENT '语句在工单内的执行序号',
  statement       LONGBLOB      NOT NULL COMMENT '单条执行语句（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）',
  statement_hash  VARCHAR(64)   NOT NULL COMMENT '明文的 SHA-256 hex（不是密文的）',
  statement_type  VARCHAR(16)   NOT NULL COMMENT 'SELECT/INSERT/UPDATE/DELETE/DDL/OTHER（StatementType）',
  status          VARCHAR(24)   NOT NULL COMMENT '小写状态码（UnitStatus）',
  lease_owner     VARCHAR(64)   NULL COMMENT 'worker 实例 id',
  lease_until     DATETIME(3)   NULL COMMENT '租约到期时间；过期且未心跳可被其他 worker 抢占',
  heartbeat_at    DATETIME(3)   NULL COMMENT 'worker 心跳时间；reaper 按超时回收 executing 单元',
  intent_written  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '已预写执行意图（SQL 可能已到生产库，禁止自动重试）',
  executed_at     DATETIME(3)   NULL COMMENT '无唯一约束：同毫秒完成是正常情况',
  affected_rows   BIGINT        NULL,
  ddl_fingerprint VARCHAR(64)   NULL COMMENT '审核时的表结构指纹，执行前复核（R_SCHEMA_DRIFT 门禁）',
  error_message   VARCHAR(1024) NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_unit_ticket_order (ticket_id, unit_order),
  -- worker 抢占主路径：WHERE intent_written=0 AND (status=? OR (status=? AND lease_until<?))
  KEY idx_unit_claim (intent_written, status, lease_until),
  -- reaper：WHERE status='executing' AND heartbeat_at<? AND executed_at IS NULL
  KEY idx_unit_heartbeat (status, heartbeat_at),
  -- 不变式：intent_written=1 只能出现在 executing/success/failed/needs_confirm；
  --        status='executing' 必然 intent_written=1
  CONSTRAINT chk_unit_intent CHECK (
    (intent_written = 0 OR status IN ('executing','success','failed','needs_confirm'))
    AND (status <> 'executing' OR intent_written = 1)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行单元 创建态写方：sqlaudit-workflow；执行态写方：sqlaudit-executor';

-- ---------------------------------------------------------------------
-- 5. 回滚 SQL（唯一写入方：sqlaudit-rollback）
-- ---------------------------------------------------------------------

CREATE TABLE t_rollback_sql (
  id                 BIGINT       PRIMARY KEY AUTO_INCREMENT,
  unit_id            BIGINT       NOT NULL,
  stmt_count         INT          NOT NULL DEFAULT 0 COMMENT '反向语句条数（明细在 t_rollback_statement）',
  total_bytes        BIGINT       NOT NULL DEFAULT 0 COMMENT '反向语句密文总字节数（估算回放规模）',
  generator          VARCHAR(32)  NOT NULL COMMENT 'MIRROR/REVERSE_DDL/BINLOG/SNAPSHOT/NONE（RollbackGenerator）',
  status             VARCHAR(16)  NOT NULL COMMENT 'generated/verified/applied/expired/not_available/failed/partial（RollbackStatus）',
  coverage           VARCHAR(16)  NOT NULL COMMENT 'FULL/PARTIAL/NONE（RollbackCoverage）',
  unavailable_reason VARCHAR(255) NULL,
  preimage_hash      VARCHAR(64)  NULL COMMENT '前镜像指纹，用于依赖检测',
  expires_at         DATETIME(3)  NULL COMMENT '前镜像 / 反向语句有效期，过期后不可回滚',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rollback_unit (unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回滚主记录 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';

CREATE TABLE t_rollback_statement (
  id           BIGINT      PRIMARY KEY AUTO_INCREMENT,
  rollback_id  BIGINT      NOT NULL,
  stmt_order   INT         NOT NULL COMMENT '回放顺序（与原行顺序无关，但必须稳定）',
  reverse_sql  LONGBLOB    NOT NULL COMMENT '带 ? 占位符的反向语句（AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）',
  value_params LONGBLOB    NULL COMMENT '该条语句的旧值参数 JSON（前镜像生产数据，AES-GCM 密文：iv(12B) || 密文+tag，见 common 的 SqlCipher）',
  applied_at   DATETIME(3) NULL COMMENT '逐条幂等回放',
  UNIQUE KEY uk_rollback_stmt_order (rollback_id, stmt_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反向语句明细 唯一写入方：sqlaudit-rollback（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 6. 审批记录（唯一写入方：sqlaudit-workflow）
--    指派对象（approver_type + approver_ref）与实际决策人（decided_by）分列存储。
-- ---------------------------------------------------------------------

CREATE TABLE t_approval (
  id            BIGINT        PRIMARY KEY AUTO_INCREMENT,
  ticket_id     BIGINT        NOT NULL,
  flow_node_id  BIGINT        NOT NULL COMMENT '生成本记录的 t_flow_node.id（审批配置可追溯）',
  node_order    INT           NOT NULL COMMENT '生效链中的组序（全局组在前、实例组在后，重新编号）',
  mode          VARCHAR(8)    NOT NULL COMMENT 'AND 会签 / OR 或签（ApprovalMode）',
  approver_type VARCHAR(16)   NOT NULL COMMENT 'USER/ROLE/INSTANCE_OWNER（ApproverType）',
  approver_ref  VARCHAR(64)   NOT NULL COMMENT 'USER=user_id；ROLE=角色 code；INSTANCE_OWNER=提交时解析出的负责人 user_id（快照）',
  result        VARCHAR(16)   NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TRANSFERRED/SKIPPED（ApprovalResult）；SKIPPED=节点解析到提交人本人，视为通过',
  decided_by    BIGINT        NULL COMMENT '实际决策人（认领 ROLE 节点、决策、转交、跳过时写入）',
  comment       VARCHAR(1024) NULL COMMENT '审批意见',
  decided_at    DATETIME(3)   NULL COMMENT '决策时间（决策 / 认领 / 转交 / 跳过时写入）',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- 防并发重复生成；其前缀 (ticket_id, node_order) 同时服务 listByTicket，无需再建一条索引
  UNIQUE KEY uk_approval_assignee (ticket_id, node_order, approver_type, approver_ref),
  KEY idx_approval_pending (result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录 唯一写入方：sqlaudit-workflow（审计相关，禁逻辑删除）';

-- ---------------------------------------------------------------------
-- 7. 执行锁（唯一写入方：sqlaudit-executor，reaper 清理同模块）
-- ---------------------------------------------------------------------

CREATE TABLE t_exec_lock (
  lock_key    VARCHAR(255) PRIMARY KEY COMMENT 'inst:{id} 或 inst:{id}:obj:{schema.table}',
  unit_id     BIGINT       NOT NULL COMMENT '持锁的执行单元',
  worker_id   VARCHAR(64)  NOT NULL COMMENT '持锁 worker 实例 id',
  acquired_at DATETIME(3)  NOT NULL,
  expires_at  DATETIME(3)  NOT NULL COMMENT '与租约同生命周期，过期由 reaper 清理',
  KEY idx_exec_lock_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行锁（并发互斥门禁） 唯一写入方：sqlaudit-executor';

-- ---------------------------------------------------------------------
-- 8. 审计日志与 hash 链（唯一写入方：sqlaudit-audit，单写入点）
-- ---------------------------------------------------------------------

CREATE TABLE t_chain_head (
  id         SMALLINT    PRIMARY KEY DEFAULT 1 COMMENT '单行表，恒为 1',
  last_seq   BIGINT      NOT NULL DEFAULT 0 COMMENT '已分配的最后一个链序号',
  last_hash  VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000' COMMENT '最新一条审计记录的 hash（创世块为 64 个 0）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_chain_head_single CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计链头（单写入点，行锁串行） 唯一写入方：sqlaudit-audit';

CREATE TABLE t_audit_log (
  id          BIGINT        PRIMARY KEY AUTO_INCREMENT COMMENT '物理主键，不参与 hash 链',
  seq         BIGINT        NOT NULL COMMENT '链序号，由 chain_head 行锁内分配',
  actor_id    BIGINT        NOT NULL,
  actor_name  VARCHAR(64)   NOT NULL,
  actor_role  VARCHAR(32)   NOT NULL COMMENT '操作人首个角色 code；系统动作写 SYSTEM',
  action      VARCHAR(64)   NOT NULL COMMENT 'LOGIN/QUERY_EXECUTED/TICKET_STATE_CHANGE/...（AuditAction）',
  target_type VARCHAR(32)   NOT NULL COMMENT '审计对象类型（AuditTargetType）',
  target_id   VARCHAR(64)   NULL,
  instance_id BIGINT        NULL COMMENT '关联实例（无则 NULL）',
  detail      LONGBLOB      NULL COMMENT 'AES-GCM 密文（摘要 + SQL 全文）；哈希算明文，密钥轮换不破链',
  result      VARCHAR(16)   NOT NULL COMMENT 'SUCCESS/REJECTED/FAILED/PARTIAL（AuditResult）',
  client_ip   VARCHAR(64)   NULL COMMENT 'X-Forwarded-For 首段，缺省 remoteAddr',
  user_agent  VARCHAR(256)  NULL COMMENT '请求 User-Agent',
  trace_id    VARCHAR(64)   NOT NULL COMMENT '请求链路 trace id',
  prev_hash   VARCHAR(64)   NOT NULL COMMENT '前一条记录的 hash（append-only 防篡改链）',
  hash        VARCHAR(64)   NOT NULL COMMENT 'SHA256(prev_hash || seq || canonical_json(明文记录))',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_seq (seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量审计（append-only + hash 链，禁逻辑删除） 唯一写入方：sqlaudit-audit';
