-- =====================================================================
-- SQL 审核平台 · 平台库迁移 V4 —— 实例 IoTDB SQL 方言（MySQL 8 方言）
--
-- 背景：IoTDB 2.x 树/表双模型是连接级 sql_dialect 开关（默认 tree，表模型须
-- 在 JDBC URL 带 ?sql_dialect=table）。t_instance 增加 iotdb_sql_dialect 列，
-- 仅 IOTDB 实例有意义，非 IOTDB 实例为 NULL（NULL 时按 TREE 处理）。
--
-- 约定：V1__schema.sql / V2__seed.sql 已冻结，不得修改；本列在新迁移追加。
-- =====================================================================

ALTER TABLE t_instance
  ADD COLUMN iotdb_sql_dialect VARCHAR(16) NULL
    COMMENT 'IOTDB 专用：TREE/TABLE（IotdbSqlDialect，决定 JDBC sql_dialect 参数；非 IOTDB 实例为 NULL）'
    AFTER default_schema;
