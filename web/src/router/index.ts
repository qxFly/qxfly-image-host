import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { TOKEN_KEY } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  // 桌面端与静态部署都使用 hash 模式，避免服务端 rewrite 配置
  history: createWebHashHistory(),
  routes: [
    {
      // 公开的分享相册：不需要登录
      path: '/s/:token',
      name: 'share',
      component: () => import('@/views/ShareView.vue'),
      meta: { public: true, title: '分享相册' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '概览' },
        },
        {
          path: 'gallery',
          name: 'gallery',
          component: () => import('@/views/GalleryView.vue'),
          meta: { title: '图库' },
        },
        {
          path: 'trash',
          name: 'trash',
          component: () => import('@/views/TrashView.vue'),
          meta: { title: '回收站', requiresSuperAdmin: true },
        },
        {
          path: 'upload',
          name: 'upload',
          component: () => import('@/views/UploadView.vue'),
          meta: { title: '上传', requiresSuperAdmin: true },
        },
        {
          path: 'storage',
          name: 'storage',
          component: () => import('@/views/StorageView.vue'),
          meta: { title: '存储设置', requiresSuperAdmin: true },
        },
        {
          path: 'shares',
          name: 'shares',
          component: () => import('@/views/SharesView.vue'),
          meta: { title: '分享链接', requiresSuperAdmin: true },
        },
        {
          path: 'duplicates',
          name: 'duplicates',
          component: () => import('@/views/DuplicateView.vue'),
          meta: { title: '重复图片', requiresSuperAdmin: true },
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/AuditView.vue'),
          meta: { title: '审计日志', requiresSuperAdmin: true },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '系统设置', requiresSuperAdmin: true },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UserView.vue'),
          meta: { title: '用户管理', requiresSuperAdmin: true },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '个人中心' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async (to) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && token) {
    return { path: '/dashboard' }
  }
  // 管理类页面（上传 / 存储设置 / 用户管理）只对超级管理员开放，普通用户直接送回图库
  if (to.meta.requiresSuperAdmin) {
    const auth = useAuthStore()
    if (!auth.user) {
      try {
        await auth.loadUser()
      } catch {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    if (!auth.isSuperAdmin) {
      ElMessage.warning('该功能仅超级管理员可用')
      return { path: '/gallery' }
    }
  }
  return true
})

export default router
