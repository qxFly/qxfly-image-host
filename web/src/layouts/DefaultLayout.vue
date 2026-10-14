<template>
  <el-container class="layout">
    <!-- 桌面端：常驻侧栏；小屏收进抽屉 -->
    <el-aside v-if="!isMobile" width="212px" class="layout-aside">
      <div class="layout-logo">
        <el-icon :size="20"><PictureFilled /></el-icon>
        <span>qxfly-image-host</span>
      </div>
      <NavMenu />
    </el-aside>

    <el-drawer
      v-model="menuVisible"
      direction="ltr"
      size="230px"
      :with-header="false"
      class="layout-drawer"
    >
      <!-- 深色底放在我自己的容器上：el-drawer 的模板根是 teleport，
           传进去的 class 与 scoped 样式落点不可靠，靠它上色会出现白底白字 -->
      <div class="layout-drawer-inner">
        <div class="layout-logo">
          <el-icon :size="20"><PictureFilled /></el-icon>
          <span>qxfly-image-host</span>
        </div>
        <NavMenu @navigate="menuVisible = false" />
      </div>
    </el-drawer>

    <el-container>
      <el-header class="layout-header">
        <el-button v-if="isMobile" link class="layout-menu-button" @click="menuVisible = true">
          <el-icon :size="20"><Menu /></el-icon>
        </el-button>
        <span class="layout-title">{{ pageTitle }}</span>
        <div class="toolbar-spacer"></div>
        <el-tag v-if="auth.user" size="small" :type="auth.isSuperAdmin ? 'warning' : 'info'" effect="plain">
          {{ auth.roleLabel }}
        </el-tag>
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

      <!-- 上传浮层挂在布局上：切换路由不会影响上传，也能一直看到进度 -->
      <UploadPanel />
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import UploadPanel from '@/components/UploadPanel.vue'
import NavMenu from '@/components/NavMenu.vue'
import { useIsMobile } from '@/utils/responsive'
import { useAuthStore } from '@/stores/auth'
import { fetchSystemInfo } from '@/api/system'
import type { SystemInfo } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const systemInfo = ref<SystemInfo | null>(null)

/** 小屏用抽屉菜单 */
const isMobile = useIsMobile()
const menuVisible = ref(false)

const pageTitle = computed(() => (route.meta.title as string) || 'qxfly-image-host')
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

/* 抽屉里的菜单要和侧栏一样是深色底，否则白底白字什么都看不见 */
.layout-drawer :deep(.el-drawer__body) {
  padding: 0;
  background: #1f2a44;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 分组标题与子项的文字颜色 */
.layout-drawer :deep(.el-sub-menu__title),
.layout-menu :deep(.el-sub-menu__title) {
  color: rgba(255, 255, 255, 0.72);
}

.layout-menu :deep(.el-sub-menu__title:hover),
.layout-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.layout-menu :deep(.el-sub-menu .el-menu) {
  background: rgba(0, 0, 0, 0.18);
}

.layout-menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  font-size: 13px;
}
</style>
