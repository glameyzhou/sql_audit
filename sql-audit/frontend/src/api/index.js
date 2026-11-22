import axios from 'axios'
import { ElMessage } from 'element-plus'
import { STORAGE_KEYS } from '../constants'

/**
 * API client——契约已冻结（对齐后端 sqlaudit-server 已实现的端点）。
 *
 * - baseURL 走 vite proxy（/api → localhost:8080），无跨域；
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
  /** 登录（返回 { token, user }） */
  login: (username, password) => http.post('/auth/login', { username, password }),
  /** 当前登录用户（刷新 user/roles） */
  me: () => http.get('/auth/me'),

  // ---- 工单 ----
  /** 提交预览：解析→规则→回滚清单→风险（无副作用） */
  preview: (sql, dialect, instanceIds = []) =>
    http.post('/tickets/preview', { sql, dialect, instanceIds }),
  /** 建单并提交审核（ERROR 命中 → 400 ERROR_RULE_BLOCKED，不建单） */
  submitTicket: (payload) => http.post('/tickets', payload),
  /** 我的工单 */
  listTickets: (creatorId) => http.get('/tickets', { params: { creatorId } }),
  /** 工单详情 */
  getTicket: (id) => http.get(`/tickets/${id}`),
  /** 提交审批（影响面评估完成后） */
  submitApproval: (id, impactAssessmentDone) =>
    http.post(`/tickets/${id}/submit-approval`, { impactAssessmentDone }),
  /** 取消工单 */
  cancelTicket: (id, actorId) => http.post(`/tickets/${id}/cancel`, null, { params: { actorId } }),

  // ---- 审批 ----
  /** 待审批列表（W2 后段按角色过滤） */
  listPendingApprovals: () => http.get('/approvals/pending'),
  /** 审批决策（防自审在后端硬校验；ticketId 为工单号） */
  decide: (ticketId, approverId, approve, comment) =>
    http.post(`/approvals/${ticketId}/decide`, { approverId, approve, comment }),
  /** CRITICAL 工单执行前二次确认 */
  confirmExecution: (ticketId, confirmerId) =>
    http.post(`/approvals/${ticketId}/confirm-execution`, { confirmerId }),

  // ---- 查询通道 ----
  /** 线上查询（LIMIT 三层保护 + 进审计链） */
  runQuery: (instanceId, sql, dialect, userId) =>
    http.post('/query', { instanceId, sql, dialect, userId }),

  // ---- 审计 ----
  /** 审计日志检索（按 seq 区间） */
  searchAuditLogs: (fromSeq = 1, toSeq = 100) =>
    http.get('/audit-logs', { params: { fromSeq, toSeq } }),
  /** 审计链完整性校验（篡改检测） */
  verifyAudit: (fromSeq = 1, toSeq = 1000000) =>
    http.get('/audit-logs/verify', { params: { fromSeq, toSeq } }),

  // ---- 执行监控 ----
  /** 按状态查执行单元（执行监控页） */
  listExecutions: (status) => http.get('/executions', { params: { status } }),
  /** 某工单的执行单元（工单详情） */
  listTicketUnits: (ticketId) => http.get(`/executions/ticket/${ticketId}`),

  // ---- 回滚管理 ----
  /** 全部回滚主记录 */
  listRollbacks: () => http.get('/rollbacks'),
  /** 执行回滚（ADMIN/DBA） */
  executeRollback: (id) => http.post(`/rollbacks/${id}/execute`),
  /** 回滚反向语句明细 */
  listRollbackStatements: (id) => http.get(`/rollbacks/${id}/statements`),

  // ---- 用户管理（ADMIN）----
  listUsers: () => http.get('/users'),
  createUser: (payload) => http.post('/users', payload),
  updateUser: (id, payload) => http.put(`/users/${id}`, payload),
  resetPassword: (id, password) => http.put(`/users/${id}/password`, { password }),
  assignRoles: (id, roles) => http.put(`/users/${id}/roles`, { roles }),

  // ---- 系统配置（只读）----
  listRules: () => http.get('/config/rules'),
  listFlows: () => http.get('/config/flows'),
  listFlowNodes: (flowId) => http.get(`/config/flows/${flowId}/nodes`),
  listInstances: () => http.get('/config/instances'),

  // ---- 系统配置（CRUD，需 ADMIN/DBA）----
  createInstance: (payload) => http.post('/config/instances', payload),
  updateInstance: (id, payload) => http.put(`/config/instances/${id}`, payload),
  deleteInstance: (id) => http.delete(`/config/instances/${id}`),

  // ---- 实例（用户视角 / 连接助手）----
  /** 当前用户可见的实例列表（后端按登录用户过滤） */
  listUserInstances: () => http.get('/instances'),
  /** 测试数据库连接（用户名/密码仅用于本次校验，不落库） */
  testConnection: (payload) => http.post('/instances/test-connection', payload),
  /** 实例下的数据库列表（查询助手选择库） */
  listDatabases: (id) => http.get(`/instances/${id}/databases`),
  /** 指定库下的表列表（库名需 URL 编码） */
  listTables: (id, db) => http.get(`/instances/${id}/databases/${encodeURIComponent(db)}/tables`),
  createRule: (payload) => http.post('/config/rules', payload),
  updateRule: (id, payload) => http.put(`/config/rules/${id}`, payload),
  deleteRule: (id) => http.delete(`/config/rules/${id}`),
  createFlow: (payload) => http.post('/config/flows', payload),
  updateFlow: (id, payload) => http.put(`/config/flows/${id}`, payload),
  deleteFlow: (id) => http.delete(`/config/flows/${id}`)
}
