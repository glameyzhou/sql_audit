import { defineStore } from 'pinia'
import { api } from '../api'
import { STORAGE_KEYS, ENV } from '../constants'

/** 从 localStorage 读 user（容错：脏数据返回 null） */
function readUser() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEYS.user) || 'null')
  } catch {
    return null
  }
}

/** 从 localStorage 读全局环境（非法/历史值如 staging 回退 test） */
function readEnv() {
  const v = localStorage.getItem(STORAGE_KEYS.env)
  return Object.prototype.hasOwnProperty.call(ENV, v) ? v : 'test'
}

/**
 * 会话 store：token + user（{ id, name, displayName, roles }）。
 * 持久化到 localStorage，api 请求拦截器直接从 localStorage 读 token；
 * 真实权限以后端校验为准。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    token: localStorage.getItem(STORAGE_KEYS.token) || null,
    user: readUser(),
    /** 全局环境（dev/test/prod），持久化 localStorage；默认 test */
    env: readEnv()
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    /** 展示名（displayName 优先，回退 name） */
    displayName: (s) => s.user?.displayName || s.user?.name || null,
    roles: (s) => s.user?.roles || [],
    /** 主角色（roles 数组第一项） */
    primaryRole: (s) => (s.user?.roles && s.user.roles[0]) || null
  },
  actions: {
    persist() {
      if (this.token) localStorage.setItem(STORAGE_KEYS.token, this.token)
      else localStorage.removeItem(STORAGE_KEYS.token)
      if (this.user) localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(this.user))
      else localStorage.removeItem(STORAGE_KEYS.user)
    },
    /** 登录：调 api.login，存 token + user */
    async login(username, password) {
      const data = await api.login(username, password)
      this.token = data.token
      this.user = data.user
      this.persist()
      return data
    },
    logout() {
      this.token = null
      this.user = null
      this.persist()
    },
    /** 切换全局环境（仅接受 dev/test/prod，非法值回退 test），同步写 localStorage */
    setEnv(v) {
      const next = Object.prototype.hasOwnProperty.call(ENV, v) ? v : 'test'
      this.env = next
      localStorage.setItem(STORAGE_KEYS.env, next)
    },
    /** 刷新当前用户信息（调 api.me） */
    async fetchMe() {
      const user = await api.me()
      this.user = user
      this.persist()
      return user
    }
  }
})
