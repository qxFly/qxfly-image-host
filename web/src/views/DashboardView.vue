<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">概览</h2>
        <p class="page-subtitle">图床的整体情况与最近上传</p>
      </div>
      <div class="page-head__actions">
        <el-button v-if="canManage" type="primary" @click="router.push('/upload')">
          <el-icon><UploadFilled /></el-icon>&nbsp;上传图片
        </el-button>
        <el-button @click="router.push('/gallery')">去图库</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div
        v-for="(card, index) in statCards"
        :key="card.label"
        class="stat-card"
        :style="{ '--delay': index * 70 + 'ms' }"
      >
        <div class="stat-card__icon" :class="'is-' + card.tone">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-card__body">
          <div class="stat-card__value">{{ card.value }}</div>
          <div class="stat-card__label">{{ card.label }}</div>
        </div>
      </div>
    </div>

    <el-card v-if="canManage" shadow="never" class="content-card">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>内容管理</span>
          <span class="text-muted">回收站、重复图片与分享链接的当前情况</span>
        </div>
      </template>
      <div class="content-grid">
        <div
          v-for="card in contentCards"
          :key="card.key"
          class="content-tile"
          :class="'is-' + card.tone"
          @click="router.push(card.to)"
        >
          <div class="content-tile__icon">
            <el-icon :size="20"><component :is="card.icon" /></el-icon>
          </div>
          <div class="content-tile__body">
            <div class="content-tile__title">{{ card.title }}</div>
            <div class="content-tile__hint">{{ card.hint }}</div>
          </div>
          <div class="content-tile__right">
            <div class="content-tile__value">
              {{ card.value }}<span class="content-tile__unit">{{ card.unit }}</span>
            </div>
            <span class="content-tile__action">{{ card.action }} →</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>存储分布</span>
          <div class="toolbar-spacer"></div>
          <el-button v-if="canManage" link type="primary" @click="router.push('/storage')">管理存储后端</el-button>
        </div>
      </template>
      <el-empty v-if="stats.typeDistribution.length === 0" description="还没有上传任何图片" :image-size="80" />
      <div v-else class="dist">
        <div v-for="item in stats.typeDistribution" :key="item.name" class="dist__row">
          <span class="dist__label">{{ item.label }}</span>
          <el-progress
            :percentage="percent(item.value)"
            :stroke-width="10"
            :show-text="false"
            style="flex: 1"
          />
          <span class="dist__value">{{ item.value }} 张</span>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>最近上传</span>
          <span class="text-muted">共 {{ stats.recent.length }} 张，按日期分组</span>
          <div class="toolbar-spacer"></div>
          <el-button link type="primary" @click="router.push('/gallery')">去图库</el-button>
          <el-button link type="primary" @click="router.push('/upload')">去上传</el-button>
        </div>
      </template>
      <el-empty v-if="stats.recent.length === 0" description="暂无图片" :image-size="80" />
      <div v-else class="recent-days">
        <section v-for="group in recentGroups" :key="group.date" class="day-block">
          <div class="day-block__head">
            <span class="day-block__label">{{ group.label }}</span>
            <span class="day-block__date">{{ group.date.slice(5) }}</span>
            <el-tag size="small" effect="plain" round>{{ group.items.length }} 张</el-tag>
            <span class="day-block__line"></span>
          </div>
          <div class="recent-grid">
            <RecentImageTile
              v-for="image in group.items"
              :key="image.id"
              :image="image"
              :can-manage="canManage"
              @links="openLinks"
              @remove="remove"
            />
          </div>
        </section>
      </div>
    </el-card>

    <LinkPickerDialog v-model="linksVisible" :image="linksImage" :mode="linksMode" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LinkPickerDialog from '@/components/LinkPickerDialog.vue'
import RecentImageTile from '@/components/RecentImageTile.vue'
import { deleteImage, fetchStats } from '@/api/images'
import { useAuthStore } from '@/stores/auth'
import { formatSize } from '@/utils/format'
import { groupImagesByDay } from '@/utils/recentGroups'
import type { ImageVO, LinkMode, StatsVO } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
/** 链接选择框（下载 / 直链 / MD 都先选线路） */
const linksVisible = ref(false)
const linksImage = ref<ImageVO | null>(null)
const linksMode = ref<LinkMode>('copy')

/** 普通用户只能浏览查看下载：删除、存储管理等入口都不显示 */
const canManage = computed(() => auth.isSuperAdmin)
const stats = ref<StatsVO>({
  totalCount: 0,
  weekCount: 0,
  maxSize: 0,
  trashCount: 0,
  trashSize: 0,
  duplicateGroups: 0,
  duplicateWastedSize: 0,
  shareCount: 0,
  shareViews: 0,
  totalSize: 0,
  todayCount: 0,
  storageConfigCount: 0,
  typeDistribution: [],
  recent: [],
})

const maxValue = computed(() => {
  return stats.value.typeDistribution.reduce((max, item) => Math.max(max, item.value), 0) || 1
})

function percent(value: number) {
  return Math.round((value / maxValue.value) * 100)
}

/** 最近上传按自然日分组，分组规则见 utils/recentGroups.ts */
const recentGroups = computed(() => groupImagesByDay(stats.value.recent))

/** 概览的四个统计卡片（图标名对应全局注册的 Element Plus 图标） */
const statCards = computed(() => [
  { label: '图片总数', value: String(stats.value.totalCount), icon: 'PictureFilled', tone: 'blue' },
  { label: '占用空间', value: formatSize(stats.value.totalSize), icon: 'Coin', tone: 'violet' },
  { label: '今日上传', value: String(stats.value.todayCount), icon: 'UploadFilled', tone: 'green' },
  { label: '近 7 天新增', value: String(stats.value.weekCount), icon: 'TrendCharts', tone: 'cyan' },
  { label: '存储后端', value: String(stats.value.storageConfigCount), icon: 'Files', tone: 'amber' },
  {
    label: '回收站',
    value: stats.value.trashCount + ' 张 / ' + formatSize(stats.value.trashSize),
    icon: 'DeleteFilled',
    tone: 'rose',
  },
])

/** 内容管理（回收站 / 重复图片 / 分享链接）的入口卡片 */
const contentCards = computed(() => [
  {
    key: 'trash',
    title: '回收站',
    icon: 'DeleteFilled',
    tone: 'rose',
    value: String(stats.value.trashCount),
    unit: '张',
    hint: stats.value.trashCount > 0 ? '占用 ' + formatSize(stats.value.trashSize) : '没有待处理的图片',
    to: '/trash',
    action: '去回收站',
  },
  {
    key: 'duplicates',
    title: '重复图片',
    icon: 'CopyDocument',
    tone: 'amber',
    value: String(stats.value.duplicateGroups),
    unit: '组',
    hint:
      stats.value.duplicateGroups > 0
        ? '每组保留一张可省 ' + formatSize(stats.value.duplicateWastedSize)
        : '没有发现重复图片',
    to: '/duplicates',
    action: '去清理',
  },
  {
    key: 'shares',
    title: '分享链接',
    icon: 'Share',
    tone: 'blue',
    value: String(stats.value.shareCount),
    unit: '个',
    hint: stats.value.shareCount > 0 ? '累计浏览 ' + stats.value.shareViews + ' 次' : '还没有创建分享',
    to: '/shares',
    action: '去管理',
  },
])

async function refresh() {
  stats.value = (await fetchStats()).data
}

/** 打开「选择线路」弹窗：下载 / 复制直链 / 复制 Markdown 都先让用户挑 CDN */
function openLinks(image: ImageVO, mode: LinkMode) {
  linksImage.value = image
  linksMode.value = mode
  linksVisible.value = true
}

async function remove(image: ImageVO) {
  try {
    await deleteImage(image.id)
    ElMessage.success('已移入回收站，可在图库的「回收站」里恢复或彻底删除')
    await refresh()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await refresh()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ================= 页头：标题 + 副标题 + 主操作 ================= */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;
}

.page-head .page-title {
  margin-bottom: 0;
}

.page-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: #8a93a5;
}

.page-head__actions {
  display: flex;
  gap: 8px;
}

/* ================= 统计卡片：现代 SaaS 观感 ================= */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 16px;
  box-shadow: 0 1px 2px rgba(31, 42, 68, 0.04);
  overflow: hidden;
  animation: stat-in 0.42s cubic-bezier(0.22, 0.9, 0.3, 1) both;
  animation-delay: var(--delay, 0ms);
  transition: transform 0.22s cubic-bezier(0.34, 1.3, 0.64, 1), box-shadow 0.22s ease, border-color 0.22s ease;
}

/* 顶部一条 3px 的品牌色高光，比整块上色更克制 */
.stat-card::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: var(--tone-gradient);
  opacity: 0.9;
}

.stat-card:hover {
  transform: translateY(-3px);
  border-color: #e4e9f2;
  box-shadow: 0 14px 30px rgba(31, 42, 68, 0.1);
}

.stat-card__icon {
  flex: 0 0 auto;
  width: 46px;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  color: #fff;
  background: var(--tone-gradient);
  box-shadow: 0 6px 14px var(--tone-shadow);
}

.stat-card__body {
  min-width: 0;
}

.stat-card__value {
  font-size: 26px;
  font-weight: 650;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: #1f2a44;
  /* 数字等宽，切换时不会跳动 */
  font-variant-numeric: tabular-nums;
}

.stat-card__label {
  margin-top: 4px;
  font-size: 13px;
  color: #6b7280;
}

.is-blue {
  --tone-gradient: linear-gradient(135deg, #4f8bff, #2f6bff);
  --tone-shadow: rgba(47, 107, 255, 0.32);
}

.is-violet {
  --tone-gradient: linear-gradient(135deg, #9b7bff, #6f4bff);
  --tone-shadow: rgba(111, 75, 255, 0.3);
}

.is-green {
  --tone-gradient: linear-gradient(135deg, #3fd08a, #12b76a);
  --tone-shadow: rgba(18, 183, 106, 0.3);
}

.is-amber {
  --tone-gradient: linear-gradient(135deg, #ffc061, #f79009);
  --tone-shadow: rgba(247, 144, 9, 0.3);
}

/* ================= 内容管理：三块可点的信息卡 ================= */
.content-card {
  margin-bottom: 18px;
}

.content-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}

.content-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid #eef1f6;
  border-radius: 14px;
  background: #fff;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.content-tile:hover {
  transform: translateY(-2px);
  border-color: #e2e8f4;
  box-shadow: 0 10px 24px rgba(31, 42, 68, 0.08);
}

.content-tile__icon {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  color: #fff;
  background: var(--tone-gradient);
  box-shadow: 0 5px 12px var(--tone-shadow);
}

.content-tile__body {
  min-width: 0;
  flex: 1;
}

.content-tile__title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2a44;
}

.content-tile__hint {
  margin-top: 2px;
  font-size: 12px;
  color: #8a93a5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-tile__right {
  text-align: right;
  flex: 0 0 auto;
}

.content-tile__value {
  font-size: 22px;
  font-weight: 650;
  line-height: 1.1;
  color: #1f2a44;
  font-variant-numeric: tabular-nums;
}

.content-tile__unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #8a93a5;
}

.content-tile__action {
  font-size: 12px;
  color: var(--el-color-primary);
}

@media (max-width: 820px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .content-tile {
    padding: 12px 14px;
  }
}

.is-cyan {
  --tone-gradient: linear-gradient(135deg, #4fd1e0, #06aed4);
  --tone-shadow: rgba(6, 174, 212, 0.3);
}

.is-rose {
  --tone-gradient: linear-gradient(135deg, #ff8fa3, #f0455f);
  --tone-shadow: rgba(240, 69, 95, 0.3);
}

@keyframes stat-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 尊重系统的「减弱动态效果」 */
@media (prefers-reduced-motion: reduce) {
  .stat-card {
    animation: none;
    transition: none;
  }
}

@media (max-width: 820px) {
  .page-head {
    align-items: flex-start;
  }

  .page-head__actions {
    width: 100%;
  }

  .stat-grid {
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 10px;
  }

  .stat-card {
    padding: 14px;
    border-radius: 14px;
  }

  .stat-card__value {
    font-size: 21px;
  }

  .stat-card__icon {
    width: 38px;
    height: 38px;
    border-radius: 11px;
  }
}

.dist__row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.dist__label {
  width: 120px;
  font-size: 13px;
}

.dist__value {
  width: 70px;
  text-align: right;
  font-size: 13px;
  color: #909399;
}

/* 按日期分组 + 紧凑网格：一天一块，块内一行能放七八张，比一行一张省得多 */
.recent-days {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.day-block__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.day-block__label {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.day-block__date {
  font-size: 12px;
  color: #a8abb2;
}

.day-block__line {
  flex: 1;
  height: 1px;
  background: #f0f2f5;
}

.recent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 10px;
}
</style>
