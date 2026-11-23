-- ---------------------------------------------------------------------
-- V6 · 自定义角色：t_role 加 rank（审批链「取最严」权重）+ 内置角色权重
-- ---------------------------------------------------------------------

ALTER TABLE t_role ADD COLUMN rank INT NOT NULL DEFAULT 0 COMMENT '审批链「取最严」权重（DEV=0/LEAD=1/DBA=2/ADMIN=3，自定义角色手工填）';

UPDATE t_role SET rank = 1 WHERE code = 'LEAD';
UPDATE t_role SET rank = 2 WHERE code = 'DBA';
UPDATE t_role SET rank = 3 WHERE code = 'ADMIN';
