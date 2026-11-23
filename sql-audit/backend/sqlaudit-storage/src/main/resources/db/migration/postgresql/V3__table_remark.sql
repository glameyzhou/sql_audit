-- =====================================================================
-- SQL 审核平台 · 平台库迁移 V3 —— 表备注词典（PostgreSQL 16 方言）
--
-- 本文件为 db/migration/mysql/V3__table_remark.sql 的 PostgreSQL 逐句翻译：
--   1. AUTO_INCREMENT -> BIGSERIAL；DATETIME(3) -> TIMESTAMP(3)。
--   2. 表/列 COMMENT 改为表外 COMMENT ON TABLE/COLUMN ... IS '...'。
--   3. UNIQUE KEY -> 表内 UNIQUE 约束。
--   4. 其余同 MySQL 版：V1/V2 已冻结，新增权限点的接口绑定行写在新迁移里
--      （INSERT ... SELECT 按权限点 code 关联，不写自增 id）。
--   5. 每条语句以行尾分号结束、语句中间不得出现行尾分号（测试基建按
--      「; + 换行」拆分重放 DML，沿用 SeedSql 的书写约束）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 表备注词典（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------
CREATE TABLE t_table_remark (
  id          BIGSERIAL    PRIMARY KEY,
  instance_id BIGINT       NOT NULL,
  schema_name VARCHAR(128) NOT NULL,
  table_name  VARCHAR(255) NOT NULL,
  remark      VARCHAR(255) NOT NULL,
  created_by  BIGINT       NOT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (instance_id, schema_name, table_name)
);

COMMENT ON TABLE t_table_remark IS '表备注词典（平台备注优先于原生注释合并展示） 唯一写入方：sqlaudit-server';
COMMENT ON COLUMN t_table_remark.instance_id IS '目标实例 id（t_instance.id，逻辑外键）';
COMMENT ON COLUMN t_table_remark.schema_name IS '库名（PG 语义为 schema）';
COMMENT ON COLUMN t_table_remark.table_name IS '表名';
COMMENT ON COLUMN t_table_remark.remark IS '平台侧备注（展示时优先于原库原生注释）';
COMMENT ON COLUMN t_table_remark.created_by IS '首次写入人（user_id）';

-- ---------------------------------------------------------------------
-- 新端点权限绑定（V2 冻结后新增接口的绑定行写在新迁移里）
--   GET /api/instances/{id}/databases/{db}/table-structure → query.run
--   GET /api/table-remarks                                   → query.run
--   PUT /api/table-remarks                                   → instance.manage
-- ---------------------------------------------------------------------
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/instances/{id}/databases/{db}/table-structure' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'GET', '/api/table-remarks' FROM t_permission p WHERE p.code = 'query.run';
INSERT INTO t_permission_api (permission_id, http_method, url_pattern)
SELECT p.id, 'PUT', '/api/table-remarks' FROM t_permission p WHERE p.code = 'instance.manage';
