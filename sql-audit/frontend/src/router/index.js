import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { API } from '../api/capabilities'
import { useSessionStore } from '../stores/session'

// 每条业务路由用 meta.api 声明「进入该页所需的接口能力」，
// 侧栏菜单（Layout.vue）与下方守卫共用同一组常量；后端仍是唯一权威。
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/dashboard/Dashboard.vue'), meta: { title: '工作台', api: API.TICKETS_MINE } },
      { path: 'tickets/submit', name: 'ticket-submit', component: () => import('../views/tickets/Submit.vue'), meta: { title: '提交变更', api: API.TICKET_CREATE } },
      { path: 'tickets', name: 'tickets', component: () => import('../views/tickets/TicketList.vue'), meta: { title: '工单中心', api: API.TICKETS_MINE } },
      { path: 'tickets/:id', name: 'ticket-detail', component: () => import('../views/tickets/Detail.vue'), meta: { title: '工单详情', api: API.TICKET_DETAIL } },
      { path: 'approval', name: 'approval', component: () => import('../views/approval/ApprovalCenter.vue'), meta: { title: '审批中心', api: API.APPROVALS_PENDING } },
      { path: 'execution', name: 'execution', component: () => import('../views/execution/Monitor.vue'), meta: { title: '执行监控', api: API.EXECUTIONS } },
      { path: 'rollback', name: 'rollback', component: () => import('../views/rollback/RollbackCenter.vue'), meta: { title: '回滚管理', api: API.ROLLBACKS } },
      { path: 'query', name: 'query', component: () => import('../views/query/QueryConsole.vue'), meta: { title: '线上查询', api: API.QUERY_RUN } },
      { path: 'audit', name: 'audit', component: () => import('../views/audit/AuditLog.vue'), meta: { title: '审计日志', api: API.AUDIT_LOGS } },
      { path: 'rules', name: 'rules', component: () => import('../views/config/RuleConfig.vue'), meta: { title: '规则配置', api: API.RULES } },
      { path: 'flows', name: 'flows', component: () => import('../views/config/FlowConfig.vue'), meta: { title: '审批流配置', api: API.FLOWS } },
      { path: 'instances', name: 'instances', component: () => import('../views/config/InstanceManage.vue'), meta: { title: '实例管理', api: API.CONFIG_INSTANCES } },
      { path: 'users', name: 'users', component: () => import('../views/admin/UserManage.vue'), meta: { title: '用户管理', api: API.USERS } },
      { path: 'roles', name: 'roles', component: () => import('../views/admin/RoleManage.vue'), meta: { title: '角色管理', api: API.ROLES } },
      { path: 'permissions', name: 'permissions', component: () => import('../views/admin/PermissionCatalog.vue'), meta: { title: '权限目录', api: API.PERMISSIONS } }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  document.title = to.meta.title ? `${to.meta.title} · SQL 审核平台` : 'SQL 审核平台'

  const session = useSessionStore()
  if (to.path === '/login') return true

  // 未登录 → 跳登录，并携带回跳地址
  if (!session.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 首次进入 / 刷新：向 /auth/me 核对一次能力集合（改权限后无需重新登录）
  await session.ensureUser()
  if (!session.user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 无该页能力 → 回工作台并提示（dashboard 自身不再重定向，避免死循环）
  const need = to.meta.api
  if (need && !session.can(need) && to.name !== 'dashboard') {
    ElMessage.warning(`暂无访问「${to.meta.title || '该页面'}」的权限`)
    return { path: '/dashboard' }
  }
  return true
})

export default router
