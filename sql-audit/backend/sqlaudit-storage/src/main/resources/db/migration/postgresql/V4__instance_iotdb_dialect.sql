-- =====================================================================
-- SQL 审核平台 · 平台库迁移 V4 —— 实例 IoTDB SQL 方言（PostgreSQL 16 方言）
--
-- 本文件为 mysql/V4__instance_iotdb_dialect.sql 的 PostgreSQL 逐句翻译：
--   ALTER TABLE ADD COLUMN + 表外 COMMENT ON COLUMN。
-- =====================================================================

ALTER TABLE t_instance ADD COLUMN iotdb_sql_dialect VARCHAR(16);

COMMENT ON COLUMN t_instance.iotdb_sql_dialect IS
  'IOTDB 专用：TREE/TABLE（IotdbSqlDialect，决定 JDBC sql_dialect 参数；非 IOTDB 实例为 NULL）';
