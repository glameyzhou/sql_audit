-- ---------------------------------------------------------------------
-- V5 · 审批链落库 + 角色解析
-- ---------------------------------------------------------------------

ALTER TABLE t_ticket ADD COLUMN needs_second_confirm BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_ticket ADD COLUMN confirmed_by BIGINT;
ALTER TABLE t_ticket ADD COLUMN confirmed_at TIMESTAMP(3);

ALTER TABLE t_approval ADD COLUMN approver_type VARCHAR(16);
ALTER TABLE t_approval ADD COLUMN approver_ref VARCHAR(64);
