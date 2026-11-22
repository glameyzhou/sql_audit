import { defineStore } from 'pinia'

/**
 * 会话 store（骨架）：W2 接 auth 后填 userinfo/token/角色；
 * 原型阶段角色切换是前端状态，真实权限以后端校验为准（原型已标注此坑）。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null,
    token: null
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    role: (s) => s.user?.role || null
  }
})
