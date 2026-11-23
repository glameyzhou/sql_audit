<template>
  <div class="layout">
    <aside class="sidebar" :class="{ collapsed }">
      <!-- 品牌区（对齐原型 .brand） -->
      <div class="brand">
        <img class="brand-mark" :src="logoUrl" alt="logo" />
        <div class="brand-txt">
          <div class="brand-name">SQL 审核平台</div>
          <div class="brand-sub">POLARSTARX · INTERNAL</div>
        </div>
      </div>
      <!-- 导航（分组 + 图标 + 激活态，对齐原型 .nav） -->
      <nav class="nav">
        <template v-for="group in menuGroups" :key="group.label">
          <div v-if="!collapsed" class="nav-group">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            :title="collapsed ? item.title : undefined"
          >
            <el-icon :size="16"><component :is="item.icon" /></el-icon>
            <span class="nav-label">{{ item.title }}</span>
          </router-link>
        </template>
      </nav>
      <div class="side-foot">
        <div class="kv"><span class="k">通道</span><span class="v ok">唯一出口已生效</span></div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <button class="icon-btn" title="折叠侧栏" @click="collapsed = !collapsed">
          <el-icon :size="16"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
        </button>
        <div class="crumb">极星动力 / <b>{{ $route.meta.title || '' }}</b></div>
        <div class="grow" />
        <div class="user-area">
          <span class="user-name">{{ session.displayName || '未登录' }}</span>
          <span class="user-role">{{ session.roleLabel }}</span>
          <el-button link size="small" @click="logout">退出登录</el-button>
        </div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, Odometer, Plus, Document, Checked, Monitor, RefreshLeft, Search, List, Operation, Connection, Coin, User, Avatar, Lock } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'
import { API } from '../api/capabilities'
import logoUrl from '../assets/logo.svg'

// 菜单分组与 router/index.js 的具名路由一一对应；
// api 字段与路由 meta.api 取自同一组能力常量（API.*），两处不会漂移。
const navGroups = [
  { label: '工作台', items: [
    { path: '/dashboard', title: '工作台', icon: Odometer, api: API.TICKETS_MINE }
  ]},
  { label: '变更链路', items: [
    { path: '/tickets/submit', title: '提交变更', icon: Plus, api: API.TICKET_CREATE },
    { path: '/tickets', title: '工单中心', icon: Document, api: API.TICKETS_MINE },
    { path: '/approval', title: '审批中心', icon: Checked, api: API.APPROVALS_PENDING },
    { path: '/execution', title: '执行监控', icon: Monitor, api: API.EXECUTIONS },
    { path: '/rollback', title: '回滚管理', icon: RefreshLeft, api: API.ROLLBACKS }
  ]},
  { label: '查询与审计', items: [
    { path: '/query', title: '线上查询', icon: Search, api: API.QUERY_RUN },
    { path: '/audit', title: '审计日志', icon: List, api: API.AUDIT_LOGS }
  ]},
  { label: '配置', items: [
    { path: '/rules', title: '规则配置', icon: Operation, api: API.RULES },
    { path: '/flows', title: '审批流配置', icon: Connection, api: API.FLOWS },
    { path: '/instances', title: '实例管理', icon: Coin, api: API.CONFIG_INSTANCES },
    { path: '/users', title: '用户管理', icon: User, api: API.USERS },
    { path: '/roles', title: '角色管理', icon: Avatar, api: API.ROLES },
    { path: '/permissions', title: '权限目录', icon: Lock, api: API.PERMISSIONS }
  ]}
]

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const collapsed = ref(false)

// 按能力过滤菜单：无该页接口能力则不展示；分组内全部被过滤时整组隐藏
const menuGroups = computed(() =>
  navGroups
    .map((g) => ({ ...g, items: g.items.filter((it) => session.can(it.api)) }))
    .filter((g) => g.items.length > 0)
)

function logout() {
  session.logout()
  router.push('/login')
}

function isActive(path) {
  if (path === '/tickets') {
    // 工单中心：列表与详情 /tickets/:id 高亮，但排除子页「提交变更」
    return route.path === '/tickets' || (route.path.startsWith('/tickets/') && route.path !== '/tickets/submit')
  }
  return route.path === path || route.path.startsWith(path + '/')
}
</script>

<style scoped>
.layout { display: flex; height: 100vh; overflow: hidden; }

/* ---- 侧栏 ---- */
.sidebar {
  width: 228px;
  flex: none;
  background: var(--sa-surface);
  border-right: 1px solid var(--sa-border);
  display: flex;
  flex-direction: column;
  transition: width 0.18s ease;
}
.sidebar.collapsed { width: 64px; }

.brand {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--sa-border);
  flex: none;
  overflow: hidden;
}
.brand-mark { width: 30px; height: 30px; flex: none; border-radius: 8px; box-shadow: 0 3px 10px rgba(15, 168, 183, 0.28); }
.brand-txt { white-space: nowrap; overflow: hidden; }
.brand-name { font-weight: 700; font-size: 13.5px; letter-spacing: 0.2px; color: var(--sa-text); }
.brand-sub { font-size: 10.5px; color: var(--sa-text-3); letter-spacing: 0.5px; }

.nav { flex: 1; overflow-y: auto; padding: 10px 8px 16px; }
.nav-group {
  font-size: 10px;
  color: var(--sa-text-3);
  letter-spacing: 1.2px;
  font-weight: 600;
  padding: 14px 10px 6px;
  white-space: nowrap;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  border-radius: var(--sa-radius-sm);
  color: var(--sa-text-2);
  white-space: nowrap;
  position: relative;
  margin-bottom: 2px;
  transition: background 0.12s, color 0.12s;
}
.nav-item:hover { background: var(--sa-surface-3); color: var(--sa-text); }
.nav-item.active { background: var(--sa-brand-soft); color: var(--sa-brand); font-weight: 600; }
.nav-item.active::before {
  content: "";
  position: absolute;
  left: -8px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--sa-brand-2);
}
.sidebar.collapsed .nav-item { justify-content: center; padding: 0; }
.sidebar.collapsed .nav-label { display: none; }

.side-foot { padding: 10px; border-top: 1px solid var(--sa-border); flex: none; }
.side-foot .kv { display: flex; justify-content: space-between; font-size: 11px; color: var(--sa-text-3); padding: 2px 0; }
.side-foot .kv .v { font-weight: 600; }
.side-foot .kv .v.ok { color: var(--sa-success); }
.sidebar.collapsed .side-foot { display: none; }

/* ---- 顶栏 / 内容区 ---- */
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  height: 56px;
  flex: none;
  background: var(--sa-surface);
  border-bottom: 1px solid var(--sa-border);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
}
.crumb { font-size: 12px; color: var(--sa-text-3); white-space: nowrap; }
.crumb b { color: var(--sa-text); font-weight: 600; }
.grow { flex: 1; }
.user-area { display: flex; align-items: center; gap: 8px; }
.user-name { color: var(--sa-text); font-size: 12px; font-weight: 600; }
.user-role {
  font-size: 11px; color: var(--sa-brand-2); background: var(--sa-brand-soft);
  padding: 2px 8px; border-radius: 6px; font-weight: 600;
}

.icon-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--sa-radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--sa-text-2);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.icon-btn:hover { background: var(--sa-surface-3); color: var(--sa-text); }

.content { flex: 1; overflow-y: auto; padding: 22px 24px 40px; }
</style>
