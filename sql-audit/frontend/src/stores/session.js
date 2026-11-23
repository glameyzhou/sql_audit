import { defineStore } from 'pinia'
import { api } from '../api'
import { STORAGE_KEYS } from '../constants'

/** 从 localStorage 读 user（容错：脏数据返回 null） */
function readUser() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEYS.user) || 'null')
  } catch {
    return null
  }
}

/**
 * 会话 store：token + UserView。
 *
 * UserView = { id, username, displayName, roles: [{ code, name }], apis: ["GET /api/tickets", …] }
 * —— `apis` 是后端下发的「当前用户可调用的接口集合」（方法 + 处理器模式），
 * 前端的菜单 / 路由 / 按钮显隐一律用 `can(API.X)` 判断，不认角色码。
 *
 * 持久化到 localStorage（刷新后菜单不闪），但每次进入应用都会重新调 `/auth/me`
 * 覆盖一次（`ensureUser`），保证改角色 / 改权限后无需重新登录即生效；
 * 后端始终是唯一权威，前端只负责「少露按钮」。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    token: localStorage.getItem(STORAGE_KEYS.token) || null,
    user: readUser(),
    /** 本次页面加载是否已向 /auth/me 核对过能力集合 */
    ready: false
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    /** 展示名（displayName 优先，回退 username） */
    displayName: (s) => s.user?.displayName || s.user?.username || null,
    /** 角色对象数组 [{ code, name }]（展示用，不参与权限判断） */
    roles: (s) => s.user?.roles || [],
    /** 角色中文名，多角色以顿号连接 */
    roleLabel: (s) => (s.user?.roles || []).map((r) => r.name || r.code).join('、') || '未分配角色',
    /**
     * 能力判断：`can('POST /api/tickets')`。
     * Set 随 user 变化重算（getter 缓存），模板与脚本里都可直接调用。
     */
    can: (s) => {
      const set = new Set(s.user?.apis || [])
      return (pattern) => set.has(pattern)
    }
  },
  actions: {
    persist() {
      if (this.token) localStorage.setItem(STORAGE_KEYS.token, this.token)
      else localStorage.removeItem(STORAGE_KEYS.token)
      if (this.user) localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(this.user))
      else localStorage.removeItem(STORAGE_KEYS.user)
    },
    /** 登录：调 api.login，存 token + UserView */
    async login(username, password) {
      const data = await api.login(username, password)
      this.token = data.token
      this.user = data.user
      this.ready = true
      this.persist()
      return data
    },
    logout() {
      this.token = null
      this.user = null
      this.ready = false
      this.persist()
    },
    /** 刷新当前用户信息与能力集合（调 /auth/me） */
    async fetchMe() {
      const user = await api.me()
      this.user = user
      this.persist()
      return user
    },
    /**
     * 首次进入 / 刷新页面时核对一次能力集合（路由守卫调用）。
     * 失败不清会话：401 由响应拦截器统一跳登录，网络抖动则沿用本地缓存的 UserView。
     */
    async ensureUser() {
      if (!this.token || this.ready) return
      try {
        await this.fetchMe()
      } catch {
        // 保留本地缓存，交由守卫判断是否还能进页面
      } finally {
        this.ready = true
      }
    }
  }
})
