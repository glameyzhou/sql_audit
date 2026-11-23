/**
 * 能力常量：`"HTTP方法 + Spring 处理器模式"` 字符串的唯一来源。
 *
 * 后端拦截器按「方法 + 处理器模式」精确查表鉴权，`/api/auth/me` 下发的 `apis`
 * 数组元素就是这些字符串；前端的菜单 / 路由 / 按钮显隐一律用 `session.can(API.X)`
 * 判断，不再出现角色码（ADMIN / DBA）或权限码（rbac.manage）字面量。
 *
 * 路径变量名必须与后端 Controller 的映射原文逐字一致（如 `{ticketId}` 不能写成 `{id}`），
 * 否则 `can()` 永远返回 false——改动这里前先核对 info.md §2.3 的接口绑定表。
 */
export const API = {
  // ---- 基础（all_users：所有登录用户默认拥有）----
  AUTH_ME: 'GET /api/auth/me',
  TICKETS_MINE: 'GET /api/tickets',
  TICKET_DETAIL: 'GET /api/tickets/{id}',
  EXECUTIONS_BY_TICKET: 'GET /api/executions/ticket/{ticketId}',
  APPROVALS_PENDING: 'GET /api/approvals/pending',
  APPROVAL_DECIDE: 'POST /api/approvals/{ticketId}/decide',
  APPROVAL_TRANSFER: 'POST /api/approvals/{ticketId}/transfer',
  DIRECTORY_USERS: 'GET /api/directory/users',
  DIRECTORY_ROLES: 'GET /api/directory/roles',

  // ---- 变更 ----
  TICKET_PREVIEW: 'POST /api/tickets/preview',
  TICKET_CREATE: 'POST /api/tickets',
  TICKET_CANCEL: 'POST /api/tickets/{id}/cancel',
  TICKETS_ALL: 'GET /api/tickets/all',
  APPROVAL_CONFIRM: 'POST /api/approvals/{ticketId}/confirm-execution',
  EXECUTIONS: 'GET /api/executions',
  ROLLBACKS: 'GET /api/rollbacks',
  ROLLBACK_STATEMENTS: 'GET /api/rollbacks/{id}/statements',
  ROLLBACK_EXECUTE: 'POST /api/rollbacks/{id}/execute',

  // ---- 用户视角的实例与库表元数据（ticket.submit / query.run 共同绑定）----
  INSTANCES: 'GET /api/instances',
  INSTANCE_DATABASES: 'GET /api/instances/{id}/databases',
  INSTANCE_TABLES: 'GET /api/instances/{id}/databases/{db}/tables',
  INSTANCE_TABLE_STRUCTURE: 'GET /api/instances/{id}/databases/{db}/table-structure',
  INSTANCE_COLUMNS: 'GET /api/instances/{id}/databases/{db}/columns',

  // ---- 表备注词典（GET 随 query.run；PUT 需 instance.manage）----
  TABLE_REMARKS: 'GET /api/table-remarks',
  TABLE_REMARK_UPDATE: 'PUT /api/table-remarks',

  // ---- 查询与审计 ----
  QUERY_RUN: 'POST /api/query',
  AUDIT_LOGS: 'GET /api/audit-logs',
  AUDIT_VERIFY: 'GET /api/audit-logs/verify',

  // ---- 配置：审核规则 ----
  RULES: 'GET /api/config/rules',
  RULE_CREATE: 'POST /api/config/rules',
  RULE_UPDATE: 'PUT /api/config/rules/{id}',
  RULE_DELETE: 'DELETE /api/config/rules/{id}',

  // ---- 配置：审批流 ----
  FLOWS: 'GET /api/config/flows',
  FLOW_CREATE: 'POST /api/config/flows',
  FLOW_UPDATE: 'PUT /api/config/flows/{id}',
  FLOW_DELETE: 'DELETE /api/config/flows/{id}',
  FLOW_NODES: 'GET /api/config/flows/{id}/nodes',
  FLOW_NODES_REPLACE: 'PUT /api/config/flows/{id}/nodes',

  // ---- 配置：实例与凭据 ----
  CONFIG_INSTANCES: 'GET /api/config/instances',
  INSTANCE_CREATE: 'POST /api/config/instances',
  INSTANCE_UPDATE: 'PUT /api/config/instances/{id}',
  INSTANCE_DELETE: 'DELETE /api/config/instances/{id}',
  INSTANCE_CREDENTIALS: 'GET /api/config/instances/{id}/credentials',
  INSTANCE_TEST_CONNECTION: 'POST /api/instances/test-connection',

  // ---- 系统管理：用户 ----
  USERS: 'GET /api/users',
  USER_CREATE: 'POST /api/users',
  USER_UPDATE: 'PUT /api/users/{id}',
  USER_PASSWORD: 'PUT /api/users/{id}/password',

  // ---- 系统管理：角色与权限（meta 元权限）----
  ROLES: 'GET /api/roles',
  ROLE_CREATE: 'POST /api/roles',
  ROLE_UPDATE: 'PUT /api/roles/{id}',
  ROLE_DELETE: 'DELETE /api/roles/{id}',
  PERMISSIONS: 'GET /api/permissions',
  USER_ROLES: 'PUT /api/users/{id}/roles'
}
