import axios from 'axios'
import { ElMessage } from 'element-plus'
import { STORAGE_KEYS } from '../constants'

/**
 * API client——契约已冻结（对齐后端 sqlaudit-server 已实现的端点）。
 *
 * - baseURL 走 vite proxy（/api → localhost:8888），无跨域；
 * - 后端错误统一返回 { code, message }（HTTP 400/409/500），此处统一弹 ElMessage.error；
 * - 成功响应直接返回业务数据（拦截器剥掉 axios 包装）；
 * - 受保护接口需 Authorization: Bearer <token>，由请求拦截器统一附加。
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

// 请求拦截器：附加 Bearer token（token 由 session store 写入 localStorage）
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(STORAGE_KEYS.token)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const status = err.response?.status
    // 登录接口本身 401（账号/密码错误）不触发跳转，避免死循环
    const isLoginApi = err.config?.url?.includes('/auth/login')
    if (status === 401 && !isLoginApi) {
      localStorage.removeItem(STORAGE_KEYS.token)
      localStorage.removeItem(STORAGE_KEYS.user)
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    const body = err.response?.data
    const msg = body?.message || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export const api = {
  // ---- 认证 ----
  /** 登录（返回 { token, user: UserView }） */
  login: (username, password) => http.post('/auth/login', { username, password }),
  /** 当前登录用户（UserView：身份 + 角色 + 可调用接口集合 apis） */
  me: () => http.get('/auth/me'),

  // ---- 目录（所有登录用户，用于各类选人 / 选角色下拉）----
  /** 有效用户清单 [{ id, username, displayName }] */
  listDirectoryUsers: () => http.get('/directory/users'),
  /** 角色清单 [{ code, name }] */
  listDirectoryRoles: () => http.get('/directory/roles'),

  // ---- 工单 ----
  /** 提交预览：解析→规则→回滚清单→风险（无副作用，单实例） */
  preview: (sql, dialect, instanceId) =>
    http.post('/tickets/preview', { sql, dialect, instanceId }),
  /** 建单并提交审核（提交即生成审批节点；ERROR 命中 / 审批链未配置 → 400，不建单） */
  submitTicket: (payload) => http.post('/tickets', payload),
  /** 我提交的工单（提交人取登录态；分页，返回 { items, total }） */
  listTickets: (page = 1, size = 20) => http.get('/tickets', { params: { page, size } }),
  /** 数据范围内的全部工单（需 ticket.view_all；分页，返回 { items, total }） */
  listAllTickets: (page = 1, size = 20) => http.get('/tickets/all', { params: { page, size } }),
  /** 工单详情（对象级访问由后端校验） */
  getTicket: (id) => http.get(`/tickets/${id}`),
  /** 取消工单（仅提交人，身份取登录态） */
  cancelTicket: (id) => http.post(`/tickets/${id}/cancel`),

  // ---- 审批 ----
  /** 待审批列表（后端只返回当前组、本人可审、非本人提交的记录） */
  listPendingApprovals: () => http.get('/approvals/pending'),
  /** 审批决策（审批人身份取登录态；防自审由后端硬校验） */
  decide: (ticketId, approve, comment) =>
    http.post(`/approvals/${ticketId}/decide`, { approve, comment }),
  /** CRITICAL 工单执行前二次确认（确认人身份取登录态） */
  confirmExecution: (ticketId) =>
    http.post(`/approvals/${ticketId}/confirm-execution`),
  /** 审批转交（代理人；转交人身份取登录态） */
  transfer: (ticketId, toUserId) =>
    http.post(`/approvals/${ticketId}/transfer`, { toUserId }),

  // ---- 查询通道 ----
  /** 线上查询（LIMIT 三层保护 + 进审计链；操作人取登录态） */
  runQuery: (instanceId, sql, dialect) =>
    http.post('/query', { instanceId, sql, dialect }),

  // ---- 审计 ----
  /** 审计日志检索（按 seq 区间 + 分页，返回 { items, total }） */
  searchAuditLogs: (fromSeq = 1, toSeq = 100, page = 1, size = 20) =>
    http.get('/audit-logs', { params: { fromSeq, toSeq, page, size } }),
  /** 审计链完整性校验（篡改检测） */
  verifyAudit: (fromSeq = 1, toSeq = 1000000) =>
    http.get('/audit-logs/verify', { params: { fromSeq, toSeq } }),

  // ---- 执行监控 ----
  /** 按状态查执行单元（执行监控页） */
  listExecutions: (status) => http.get('/executions', { params: { status } }),
  /** 某工单的执行单元（工单详情） */
  listTicketUnits: (ticketId) => http.get(`/executions/ticket/${ticketId}`),

  // ---- 回滚管理 ----
  /** 回滚主记录（数据范围内；分页，返回 { items, total }；ticketId 可选按工单过滤） */
  listRollbacks: (page = 1, size = 20, ticketId = null) =>
    http.get('/rollbacks', {
      params: { page, size, ...(ticketId != null ? { ticketId } : {}) }
    }),
  /** 执行回滚（需 rollback.execute） */
  executeRollback: (id) => http.post(`/rollbacks/${id}/execute`),
  /** 回滚反向语句明细（含前镜像旧值，需 rollback.view） */
  listRollbackStatements: (id) => http.get(`/rollbacks/${id}/statements`),

  // ---- 用户管理（user.manage）----
  listUsers: () => http.get('/users'),
  /** 新建用户（不带角色；角色统一走 assignRoles，属 rbac.manage） */
  createUser: (payload) => http.post('/users', payload),
  updateUser: (id, payload) => http.put(`/users/${id}`, payload),
  resetPassword: (id, password) => http.put(`/users/${id}/password`, { password }),
  /** 分配角色（roles 为角色 code 数组，需 rbac.manage） */
  assignRoles: (id, roles) => http.put(`/users/${id}/roles`, { roles }),

  // ---- 系统配置（只读）----
  listRules: () => http.get('/config/rules'),
  listFlows: () => http.get('/config/flows'),
  listFlowNodes: (flowId) => http.get(`/config/flows/${flowId}/nodes`),
  listInstances: () => http.get('/config/instances'),

  // ---- 系统配置（CRUD）----
  createInstance: (payload) => http.post('/config/instances', payload),
  updateInstance: (id, payload) => http.put(`/config/instances/${id}`, payload),
  deleteInstance: (id) => http.delete(`/config/instances/${id}`),
  /** 实例凭据概要（仅账号名，不回显密码；编辑回填用） */
  getInstanceCredentials: (id) => http.get(`/config/instances/${id}/credentials`),

  // ---- 实例（用户视角 / 连接助手）----
  /** 当前用户可见的实例列表（后端按登录用户过滤） */
  listUserInstances: () => http.get('/instances'),
  /** 测试数据库连接（用户名/密码仅用于本次校验，不落库） */
  testConnection: (payload) => http.post('/instances/test-connection', payload),
  /** 实例下的数据库列表（查询助手选择库） */
  listDatabases: (id) => http.get(`/instances/${id}/databases`),
  /** 指定库下的表列表（[{ name, comment }]；comment 为原库原生注释，可为 null） */
  listTables: (id, db) => http.get(`/instances/${id}/databases/${encodeURIComponent(db)}/tables`),
  /** 指定库某表的列名清单（table 走 query 参数，PG 为 schema.table 限定名） */
  listColumns: (id, db, table) =>
    http.get(`/instances/${id}/databases/${encodeURIComponent(db)}/columns`, { params: { table } }),
  /** 表结构面板（[{ ordinal, name, dataType, nullable, key, defaultValue, comment }]） */
  listTableStructure: (id, db, table) =>
    http.get(`/instances/${id}/databases/${encodeURIComponent(db)}/table-structure`, { params: { table } }),
  /** 平台表备注词典（[{ tableName, remark }]；仅当前实例 + 库） */
  listTableRemarks: (instanceId, schemaName) =>
    http.get('/table-remarks', { params: { instanceId, schemaName } }),
  /** 写入 / 覆盖备注（remark 为空串 = 清空删除） */
  saveTableRemark: (payload) => http.put('/table-remarks', payload),
  createRule: (payload) => http.post('/config/rules', payload),
  updateRule: (id, payload) => http.put(`/config/rules/${id}`, payload),
  deleteRule: (id) => http.delete(`/config/rules/${id}`),
  /** 新建流程（{ name, instanceId | null, riskLevel, enabled }；同范围+风险重复 → 409） */
  createFlow: (payload) => http.post('/config/flows', payload),
  /** 编辑流程（仅 name / enabled，范围与风险等级创建后不可改） */
  updateFlow: (id, payload) => http.put(`/config/flows/${id}`, payload),
  deleteFlow: (id) => http.delete(`/config/flows/${id}`),
  /** 整体替换流程节点（[{ nodeOrder, approverType, approverRef, mode }]） */
  replaceFlowNodes: (flowId, nodes) => http.put(`/config/flows/${flowId}/nodes`, nodes),

  // ---- 权限目录（只读，rbac.manage；目录与接口绑定由 DML 维护）----
  listPermissions: () => http.get('/permissions'),

  // ---- 角色管理（rbac.manage）----
  listRoles: () => http.get('/roles'),
  createRole: (payload) => http.post('/roles', payload),
  updateRole: (id, payload) => http.put(`/roles/${id}`, payload),
  deleteRole: (id) => http.delete(`/roles/${id}`)
}
