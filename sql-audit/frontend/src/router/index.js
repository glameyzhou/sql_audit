import { createRouter, createWebHistory } from 'vue-router'

// 视图骨架对齐 UI 原型（sql-audit-ui-prototype.html）的 9 大模块。
// W2 接 API 时逐视图替换占位内容；路由结构已定版，不再动。
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/dashboard/Dashboard.vue'), meta: { title: '工作台', icon: 'Odometer' } },
      { path: 'tickets/submit', name: 'ticket-submit', component: () => import('../views/tickets/Submit.vue'), meta: { title: '提交变更', icon: 'EditPen' } },
      { path: 'tickets', name: 'tickets', component: () => import('../views/tickets/TicketList.vue'), meta: { title: '工单中心', icon: 'Tickets' } },
      { path: 'tickets/:id', name: 'ticket-detail', component: () => import('../views/tickets/Detail.vue'), meta: { title: '工单详情' } },
      { path: 'approval', name: 'approval', component: () => import('../views/approval/ApprovalCenter.vue'), meta: { title: '审批中心', icon: 'Stamp' } },
      { path: 'execution', name: 'execution', component: () => import('../views/execution/Monitor.vue'), meta: { title: '执行监控', icon: 'Monitor' } },
      { path: 'rollback', name: 'rollback', component: () => import('../views/rollback/RollbackCenter.vue'), meta: { title: '回滚管理', icon: 'RefreshLeft' } },
      { path: 'query', name: 'query', component: () => import('../views/query/QueryConsole.vue'), meta: { title: '线上查询', icon: 'Search' } },
      { path: 'audit', name: 'audit', component: () => import('../views/audit/AuditLog.vue'), meta: { title: '审计日志', icon: 'Document' } },
      { path: 'settings', name: 'settings', component: () => import('../views/settings/Settings.vue'), meta: { title: '系统配置', icon: 'Setting' } }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · SQL 审核平台` : 'SQL 审核平台'
})

export default router
