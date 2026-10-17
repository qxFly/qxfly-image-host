<template>
  <el-container class="layout">
    <!-- 桌面端：常驻侧栏；小屏收进抽屉 -->
    <el-aside v-if="!isMobile" width="212px" class="layout-aside">
      <div class="layout-logo">
        <!-- <el-icon :size="20"><PictureFilled /></el-icon> -->
        <span class="layout-logo font">QXFLY 图床</span>
      </div>
      <NavMenu />
    </el-aside>

    <el-drawer
      v-model="menuVisible"
      direction="ltr"
      size="230px"
      :with-header="false"
      class="layout-drawer">
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
        <el-button
          v-if="isMobile"
          link
          class="layout-menu-button"
          aria-label="打开导航菜单"
          @click="menuVisible = true">
          <el-icon :size="20"><Menu /></el-icon>
        </el-button>
        <span class="layout-title">{{ pageTitle }}</span>
        <div class="toolbar-spacer"></div>
        <el-tag
          v-if="auth.user"
          size="small"
          :type="auth.isSuperAdmin ? 'warning' : 'info'"
          effect="plain">
          {{ auth.roleLabel }}
        </el-tag>
        <el-tag v-if="systemInfo" size="small" type="info" effect="plain"
          >v{{ systemInfo.version }}</el-tag
        >
        <el-dropdown @command="onCommand">
          <span class="layout-user">
            <el-avatar :size="28">{{ avatarText }}</el-avatar>
            <span class="layout-username">{{ displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided
                >退出登录</el-dropdown-item
              >
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
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import UploadPanel from "@/components/UploadPanel.vue";
import NavMenu from "@/components/NavMenu.vue";
import { useIsMobile } from "@/utils/responsive";
import { useAuthStore } from "@/stores/auth";
import { fetchSystemInfo } from "@/api/system";
import type { SystemInfo } from "@/types";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const systemInfo = ref<SystemInfo | null>(null);

/** 小屏用抽屉菜单 */
const isMobile = useIsMobile();
const menuVisible = ref(false);

const pageTitle = computed(
  () => (route.meta.title as string) || "qxfly-image-host",
);
const displayName = computed(
  () => auth.user?.nickname || auth.user?.username || "未登录",
);
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase());

onMounted(async () => {
  if (!auth.user) {
    try {
      await auth.loadUser();
    } catch {
      // 拦截器已处理 401
    }
  }
  try {
    systemInfo.value = (await fetchSystemInfo()).data;
  } catch {
    // 后端未启动时不影响布局渲染
  }
});

async function onCommand(command: string) {
  if (command === "profile") {
    router.push("/profile");
    return;
  }
  try {
    await ElMessageBox.confirm("确认退出当前账号？", "提示", {
      type: "warning",
    });
  } catch {
    return;
  }
  auth.logout();
  ElMessage.success("已退出登录");
  router.push("/login");
}
</script>

<style scoped>
/* 页面只占一个视口高度：外层不滚，固定顶栏/侧栏，只有主内容区滚动。 */
.layout {
  height: 100vh;
  min-height: 0;
  overflow: hidden;
}

.layout-aside {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  flex: 0 0 212px;
}

.layout > .el-container {
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.layout-header {
  flex: 0 0 60px;
}

.layout-main {
  flex: 1 1 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 侧栏 / 抽屉 / 顶栏配色（日系清新）。scoped + :deep() 的优先级高于全局层，
   所以颜色必须写在这里，全局 main.css 压不过来。 */
.layout-aside {
  background: var(--jf-surface);
  border-right: var(--jf-hair);
  box-shadow: none;
}

.layout-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--jf-text);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: var(--jf-hair);
}

.layout-logo.font {
  font-weight: 900 !important;
}

.layout-menu {
  flex: 1;
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--jf-text-2);
  --el-menu-hover-bg-color: #f3f8fd;
  --el-menu-active-color: var(--jf-sky-deep);
}

.layout-menu :deep(.el-menu-item.is-active) {
  background: #eaf5fe;
  color: var(--jf-sky-deep);
  border-right: none;
}

.layout-header {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--jf-surface);
  border-bottom: var(--jf-hair);
}

.layout-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--jf-text);
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
  color: var(--jf-text);
}

.layout-main {
  padding: 0;
  overflow: auto;
  background: var(--jf-bg);
}

/* 抽屉底色与侧栏一致（浅色），否则会白底白字 */
.layout-drawer :deep(.el-drawer__body) {
  padding: 0;
  background: var(--jf-surface);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 分组标题与子项的文字颜色：深色，不再继承白色 */
.layout-drawer :deep(.el-sub-menu__title),
.layout-menu :deep(.el-sub-menu__title) {
  color: var(--jf-text-2);
}

.layout-menu :deep(.el-sub-menu__title:hover),
.layout-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--jf-sky-deep);
  background: #f3f8fd;
}

.layout-menu :deep(.el-sub-menu .el-menu) {
  background: #f6f8fa;
}

.layout-menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  font-size: 13px;
  line-height: 24px;
  padding: 4px 12px;
  box-sizing: border-box;
}

/* 展开动画用 GPU，避免子项插入时重排导致的抖动 */
.layout-menu :deep(.el-menu--inline) {
  will-change: height;
}

.layout-menu-button {
  color: var(--jf-text-2);
}

.layout-drawer-inner {
  background: var(--jf-surface);
  /* 滑入动画期间整块做合成层，避免字体逐帧重新栅格化导致的上下抖动 */
  will-change: transform;
  transform: translateZ(0);
}

.layout-drawer .el-drawer {
  background: var(--jf-surface);
  border-radius: 0;
  box-shadow: none;
}

</style>
