-- =====================================================================
-- SQL 审核平台 · 平台库迁移 V3 —— 表备注词典（MySQL 8 方言）
--
-- 背景：查询通道只读，SHOW CREATE TABLE / DESC 均被设计拒绝；表结构改经
-- GET /api/instances/{id}/databases/{db}/table-structure 展示。本迁移
-- 增加平台侧表备注词典（t_table_remark）——展示时平台备注优先于原库原生
-- 注释合并（有平台备注显示平台备注，否则回落原生注释），惰性维护，不做批量导入。
--
-- 约定（本文件与 V1/V2 的拆分约定不同，请勿用 V1 的规则约束它）：
--   1. V1__schema.sql / V2__seed.sql 已冻结，不得修改；新增权限点的接口绑定行
--      （t_permission_api）写在新迁移里，本文件即首例（INSERT ... SELECT 按
--      权限点 code 关联，不写自增 id）。
--   2. 本文件同时含 DDL 与 DML，自洽即可；与 postgresql/V3__table_remark.sql
--      是逐句翻译关系（只有 V2__seed.sql 有「两方言逐字节相同」契约）。
--   3. 每条语句以行尾分号结束、语句中间不得出现行尾分号（测试基建按「; + 换行」
--      拆分重放 DML，沿用 SeedSql 的书写约束）。
--   4. 表备注行在「清空备注 = 删除行」语义下由 sqlaudit-server 维护。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 表备注词典（唯一写入方：sqlaudit-server）
-- ---------------------------------------------------------------------
CREATE TABLE `t_table_remark` (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  instance_id BIGINT       NOT NULL COMMENT '目标实例 id（t_instance.id，逻辑外键）',
  schema_name VARCHAR(128) NOT NULL COMMENT '库名（PG 语义为 schema）',
  table_name  VARCHAR(255) NOT NULL COMMENT '表名',
  remark      VARCHAR(255) NOT NULL COMMENT '平台侧备注（展示时优先于原库原生注释）',
  created_by  BIGINT       NOT NULL COMMENT '首次写入人（user_id）',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_table_remark (instance_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表备注词典（平台备注优先于原生注释合并展示） 唯一写入方：sqlaudit-server';

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
