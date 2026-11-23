-- ---------------------------------------------------------------------
-- V5 · 审批链落库 + 角色解析
-- ---------------------------------------------------------------------

ALTER TABLE t_ticket ADD COLUMN needs_second_confirm TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要执行前二次确认（CRITICAL 链）';
ALTER TABLE t_ticket ADD COLUMN confirmed_by BIGINT NULL COMMENT '执行前二次确认人（user_id）';
ALTER TABLE t_ticket ADD COLUMN confirmed_at DATETIME(3) NULL COMMENT '执行前二次确认时间';

ALTER TABLE t_approval ADD COLUMN approver_type VARCHAR(16) NULL COMMENT '审批人类型 ROLE/USER/INSTANCE_OWNER';
ALTER TABLE t_approval ADD COLUMN approver_ref VARCHAR(64) NULL COMMENT '审批人 ref：ROLE=角色码，USER=用户id，INSTANCE_OWNER=owner';
