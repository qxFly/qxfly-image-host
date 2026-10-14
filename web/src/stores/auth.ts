import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { TOKEN_KEY } from '@/api/http'
import type { UserVO } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserVO | null>(null)
  const isLoggedIn = computed(() => token.value.length > 0)

  /**
   * 是不是超级管理员。
   * 兼容历史数据里的 ADMIN（后端会归一化成 SUPER_ADMIN），默认按普通用户处理。
   */
  const isSuperAdmin = computed(() => {
    const role = user.value?.role
    return role === 'SUPER_ADMIN' || role === 'ADMIN'
  })
  const roleLabel = computed(
    () => user.value?.roleLabel || (isSuperAdmin.value ? '超级管理员' : '普通用户'),
  )

  function setToken(value: string) {
    token.value = value
    localStorage.setItem(TOKEN_KEY, value)
  }

  async function login(username: string, password: string) {
    const result = await authApi.login({ username, password })
    setToken(result.data.token)
    user.value = result.data.user
    return result.data
  }

  async function loadUser() {
    if (!token.value) {
      return null
    }
    const result = await authApi.fetchMe()
    user.value = result.data
    return result.data
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, isLoggedIn, isSuperAdmin, roleLabel, setToken, login, loadUser, logout }
})
