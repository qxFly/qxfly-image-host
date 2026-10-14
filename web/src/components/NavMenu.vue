<template>
  <el-menu
    :default-active="activeMenu"
    :default-openeds="openedGroups"
    router
    class="layout-menu"
    @select="onSelect"
  >
    <el-menu-item index="/dashboard">
      <el-icon><DataLine /></el-icon><span>概览</span>
    </el-menu-item>
    <el-menu-item index="/gallery">
      <el-icon><PictureRounded /></el-icon><span>图库</span>
    </el-menu-item>
    <el-menu-item v-if="auth.isSuperAdmin" index="/upload">
      <el-icon><UploadFilled /></el-icon><span>上传</span>
    </el-menu-item>

    <!-- 管理类功能按用途收进两个分组，侧栏不再一长串 -->
    <template v-if="auth.isSuperAdmin">
      <el-sub-menu index="content">
        <template #title>
          <el-icon><Files /></el-icon><span>内容管理</span>
        </template>
        <el-menu-item index="/trash">
          <el-icon><DeleteFilled /></el-icon><span>回收站</span>
        </el-menu-item>
        <el-menu-item index="/duplicates">
          <el-icon><CopyDocument /></el-icon><span>重复图片</span>
        </el-menu-item>
        <el-menu-item index="/shares">
          <el-icon><Share /></el-icon><span>分享链接</span>
        </el-menu-item>
      </el-sub-menu>

      <el-sub-menu index="system">
        <template #title>
          <el-icon><Setting /></el-icon><span>系统管理</span>
        </template>
        <el-menu-item index="/storage">
          <el-icon><Coin /></el-icon><span>存储设置</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><UserFilled /></el-icon><span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Tools /></el-icon><span>系统设置</span>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Document /></el-icon><span>审计日志</span>
        </el-menu-item>
      </el-sub-menu>
    </template>

    <el-menu-item index="/profile">
      <el-icon><User /></el-icon><span>个人中心</span>
    </el-menu-item>
  </el-menu>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 抽屉里点一下就关（桌面端不影响） */
const emit = defineEmits<{ (event: 'navigate'): void }>()
const route = useRoute()
const auth = useAuthStore()

const CONTENT_ROUTES = ['/trash', '/duplicates', '/shares']
const SYSTEM_ROUTES = ['/storage', '/users', '/settings', '/audit']

const activeMenu = computed(() => {
  const path = route.path
  return (
    [...CONTENT_ROUTES, ...SYSTEM_ROUTES, '/upload', '/profile', '/gallery', '/dashboard'].find((item) =>
      path.startsWith(item),
    ) || path
  )
})

/** 当前所在的分组默认展开，其他分组保持收起 */
const openedGroups = computed(() => {
  const opened: string[] = []
  if (CONTENT_ROUTES.some((item) => route.path.startsWith(item))) {
    opened.push('content')
  }
  if (SYSTEM_ROUTES.some((item) => route.path.startsWith(item))) {
    opened.push('system')
  }
  return opened
})

function onSelect() {
  emit('navigate')
}
</script>
