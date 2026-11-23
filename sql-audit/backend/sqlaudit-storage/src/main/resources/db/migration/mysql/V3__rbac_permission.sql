-- ---------------------------------------------------------------------
-- 角色-权限可配置化（sql-audit-rbac-权限可配置化设计.md）
-- t_permission：权限点目录（由 PermissionCode 枚举启动时 insert-if-absent 同步）
-- t_role_permission：角色 ↔ 权限点覆盖（customized=true 后生效，未配置走代码兜底）
-- ---------------------------------------------------------------------

CREATE TABLE `t_permission` (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(32)  NOT NULL COMMENT '权限点 code（= PermissionCode 枚举名）',
  name        VARCHAR(64)  NOT NULL,
  description VARCHAR(255) NULL,
  customized  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '界面是否已配置过：0=代码兜底默认角色，1=按 t_role_permission',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点目录 唯一写入方：sqlaudit-server';

CREATE TABLE t_role_permission (
  permission_id BIGINT      NOT NULL,
  role_id       BIGINT      NOT NULL,
  granted_by    BIGINT      NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (permission_id, role_id),
  KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限点关联 唯一写入方：sqlaudit-server';
