<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">重复图片</h2>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>按内容 sha256 分组，只列完全相同的图片</span>
          <div class="toolbar-spacer"></div>
          <el-tag v-if="result" type="warning" effect="plain">
            共 {{ result.total }} 组，可回收 {{ formatSize(result.wastedSize) }}
          </el-tag>
          <el-button
            type="danger"
            :disabled="!result || result.total === 0"
            :loading="working"
            @click="cleanupAll"
          >
            全部清理（每组保留最早一张）
          </el-button>
          <el-button :loading="loading" @click="load(page)">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>清理是「移入回收站」，不是物理删除</template>
        每组保留**最早上传**的那张（最可能是图库在用的记录），其余移入回收站；
        确认无误后再去回收站彻底删除，误判还能恢复。只比对内容完全相同的文件，不做相似图检测。
      </el-alert>

      <el-empty v-if="!result || result.total === 0" description="没有发现重复图片" :image-size="80" />

      <div v-for="group in result ? result.groups : []" :key="group.hash" class="dup-group">
        <div class="dup-group__head">
          <span class="mono">{{ group.hash.slice(0, 16) }}…</span>
          <span class="text-muted">
            {{ group.count }} 张 · 共 {{ formatSize(group.totalSize) }} · 可省
            {{ formatSize(reclaimable(group)) }}
          </span>
          <div class="toolbar-spacer"></div>
          <el-button size="small" type="danger" plain :loading="working" @click="cleanupOne(group)">
            只保留最早一张
          </el-button>
        </div>
        <div class="dup-group__list">
          <div v-for="image in group.images" :key="image.id" class="dup-item" :class="{ 'is-keep': image.keep }">
            <el-image class="dup-item__thumb" :src="thumbOf(image)" fit="cover" :preview-src-list="[thumbOf(image)]" />
            <div class="dup-item__meta">
              <span class="dup-item__name" :title="image.originalName || image.objectKey">
                {{ image.originalName || image.objectKey }}
              </span>
              <span class="text-muted">#{{ image.id }} · {{ formatSize(image.size || 0) }} · {{ image.createdAt }}</span>
            </div>
            <el-tag v-if="image.keep" size="small" type="success" effect="plain">保留</el-tag>
          </div>
        </div>
      </div>

      <div v-if="result && result.pages > 1" class="toolbar" style="justify-content: center; margin-top: 12px">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="result.total"
          :current-page="page"
          :page-size="size"
          @current-change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cleanupDuplicates, fetchDuplicates, type DuplicateGroup, type DuplicateResult } from '@/api/duplicates'
import { formatSize } from '@/utils/format'

const loading = ref(false)
const working = ref(false)
const result = ref<DuplicateResult | null>(null)
const page = ref(1)
const size = ref(10)

/** 用记录里的原始直链展示（本地存储就是后端自己的 /files/... 地址） */
function thumbOf(image: { url?: string }) {
  return image.url || ''
}

function reclaimable(group: DuplicateGroup) {
  const keepSize = group.images.filter((item) => item.keep).reduce((sum, item) => sum + (item.size || 0), 0)
  return Math.max(0, group.totalSize - keepSize)
}

async function load(target?: number) {
  if (typeof target === 'number') {
    page.value = target
  }
  loading.value = true
  try {
    result.value = (await fetchDuplicates(page.value, size.value)).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function cleanupOne(group: DuplicateGroup) {
  await ElMessageBox.confirm(
    '这一组的 ' + group.count + ' 张里，只保留最早的那张，其余 ' + (group.count - 1) + ' 张移入回收站。继续？',
    '清理重复图片',
    { type: 'warning' },
  )
  working.value = true
  try {
    const moved = (await cleanupDuplicates(group.hash)).data
    ElMessage.success('已把 ' + moved + ' 张移入回收站')
    await load(page.value)
  } catch {
    // 取消或失败
  } finally {
    working.value = false
  }
}

async function cleanupAll() {
  const total = result.value ? result.value.total : 0
  await ElMessageBox.confirm(
    '将对全部 ' + total + ' 组执行清理：每组保留最早的一张，其余移入回收站。继续？',
    '全部清理',
    { type: 'warning' },
  )
  working.value = true
  try {
    const moved = (await cleanupDuplicates()).data
    ElMessage.success('已把 ' + moved + ' 张移入回收站，可在回收站确认后再彻底删除')
    await load(1)
  } catch {
    // 取消或失败
  } finally {
    working.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.dup-group {
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 12px;
}

.dup-group__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.dup-group__list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.dup-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid #eef0f4;
  border-radius: 8px;
  background: #fff;
  max-width: 420px;
}

.dup-item.is-keep {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}

.dup-item__thumb {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  flex: 0 0 auto;
}

.dup-item__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  font-size: 12px;
}

.dup-item__name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  max-width: 260px;
}
</style>
