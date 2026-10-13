<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">概览</h2>

    <div class="stat-grid">
      <el-card shadow="never">
        <div class="stat-value">{{ stats.totalCount }}</div>
        <div class="stat-label">图片总数</div>
      </el-card>
      <el-card shadow="never">
        <div class="stat-value">{{ formatSize(stats.totalSize) }}</div>
        <div class="stat-label">占用空间</div>
      </el-card>
      <el-card shadow="never">
        <div class="stat-value">{{ stats.todayCount }}</div>
        <div class="stat-label">今日上传</div>
      </el-card>
      <el-card shadow="never">
        <div class="stat-value">{{ stats.storageConfigCount }}</div>
        <div class="stat-label">存储后端</div>
      </el-card>
    </div>

    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>存储分布</span>
          <div class="toolbar-spacer"></div>
          <el-button link type="primary" @click="router.push('/storage')">管理存储后端</el-button>
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
          <div class="toolbar-spacer"></div>
          <el-button link type="primary" @click="router.push('/upload')">去上传</el-button>
        </div>
      </template>
      <el-empty v-if="stats.recent.length === 0" description="暂无图片" :image-size="80" />
      <div v-else class="image-grid">
        <div v-for="image in stats.recent" :key="image.id" class="image-card">
          <el-image class="image-card__thumb" :src="image.thumbUrl || image.url" fit="cover" lazy>
            <template #error>
              <div class="image-card__thumb" style="display: flex; align-items: center; justify-content: center">
                <el-icon :size="22" color="#c0c4cc"><Picture /></el-icon>
              </div>
            </template>
          </el-image>
          <div class="image-card__body">
            <div class="image-card__name" :title="image.originalName">{{ image.originalName }}</div>
            <div class="image-card__meta">
              <span>{{ formatSize(image.size) }}</span>
              <span>{{ formatDate(image.createdAt).slice(5, 16) }}</span>
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchStats } from '@/api/images'
import { formatDate, formatSize } from '@/utils/format'
import type { StatsVO } from '@/types'

const router = useRouter()
const loading = ref(false)
const stats = ref<StatsVO>({
  totalCount: 0,
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

onMounted(async () => {
  loading.value = true
  try {
    stats.value = (await fetchStats()).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
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
</style>
