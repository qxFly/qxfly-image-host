<template>
  <el-container class="layout">
    <el-aside width="212px" class="layout-aside">
      <div class="layout-logo">
        <el-icon :size="20"><PictureFilled /></el-icon>
        <span>PicHost</span>
      </div>
      <el-menu :default-active="activeMenu" router class="layout-menu">
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon><span>概览</span>
        </el-menu-item>
        <el-menu-item index="/gallery">
          <el-icon><PictureRounded /></el-icon><span>图库</span>
        </el-menu-item>
        <el-menu-item index="/upload">
          <el-icon><UploadFilled /></el-icon><span>上传</span>
        </el-menu-item>
        <el-menu-item index="/storage">
          <el-icon><Coin /></el-icon><span>存储设置</span>
        </el-menu-item>
        <el-menu-item index="/profile">
          <el-icon><User /></el-icon><span>个人中心</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <span class="layout-title">{{ pageTitle }}</span>
        <div class="toolbar-spacer"></div>
        <el-tag v-if="systemInfo" size="small" type="info" effect="plain">v{{ systemInfo.version }}</el-tag>
        <el-dropdown @command="onCommand">
          <span class="layout-user">
            <el-avatar :size="28">{{ avatarText }}</el-avatar>
            <span class="layout-username">{{ displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { fetchSystemInfo } from '@/api/system'
import type { SystemInfo } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const systemInfo = ref<SystemInfo | null>(null)

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string) || 'PicHost')
const displayName = computed(() => auth.user?.nickname || auth.user?.username || '未登录')
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase())

onMounted(async () => {
  if (!auth.user) {
    try {
      await auth.loadUser()
    } catch {
      // 拦截器已处理 401
    }
  }
  try {
    systemInfo.value = (await fetchSystemInfo()).data
  } catch {
    // 后端未启动时不影响布局渲染
  }
})

async function onCommand(command: string) {
  if (command === 'profile') {
    router.push('/profile')
    return
  }
  try {
    await ElMessageBox.confirm('确认退出当前账号？', '提示', { type: 'warning' })
  } catch {
    return
  }
  auth.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.layout-aside {
  background: #1f2a44;
  display: flex;
  flex-direction: column;
}

.layout-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 2px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.layout-menu {
  flex: 1;
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: rgba(255, 255, 255, 0.72);
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.08);
  --el-menu-active-color: #ffffff;
}

.layout-menu :deep(.el-menu-item.is-active) {
  background: rgba(64, 158, 255, 0.22);
  border-right: 3px solid #409eff;
}

.layout-header {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}

.layout-title {
  font-size: 16px;
  font-weight: 600;
}

.layout-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.layout-username {
  font-size: 14px;
}

.layout-main {
  padding: 0;
  overflow: auto;
  background: #f5f7fa;
}
</style>
