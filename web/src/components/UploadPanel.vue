<template>
  <div v-if="store.tasks.length" class="upload-panel">
    <div class="upload-panel__head" @click="expanded = !expanded">
      <el-icon class="upload-panel__icon"><UploadFilled /></el-icon>
      <span class="upload-panel__title">
        {{ store.activeCount > 0 ? '上传中 ' + store.activeCount + ' 张' : '上传任务' }}
      </span>
      <span v-if="store.activeCount > 0" class="upload-panel__meta">
        {{ store.overallProgress }}% · {{ formatSpeed(store.totalSpeed) }} · 剩 {{ formatDuration(store.overallEta) }}
      </span>
      <span v-else class="upload-panel__meta">
        完成 {{ store.successTasks.length }}<template v-if="store.failedCount"> · 失败 {{ store.failedCount }}</template>
      </span>
      <div class="toolbar-spacer"></div>
      <el-icon><ArrowUp v-if="expanded" /><ArrowDown v-else /></el-icon>
    </div>

    <el-progress
      v-if="store.activeCount > 0"
      :percentage="store.overallProgress"
      :show-text="false"
      :stroke-width="5"
      style="margin: 6px 0"
    />

    <div v-if="expanded" class="upload-panel__list">
      <div v-for="task in store.tasks.slice(0, 10)" :key="task.id" class="upload-panel__item">
        <span class="upload-panel__name" :title="task.name">{{ task.name }}</span>
        <span class="upload-panel__state" :class="'is-' + task.status">{{ statusText(task) }}</span>
      </div>
      <div v-if="store.tasks.length > 10" class="upload-panel__more text-muted">
        还有 {{ store.tasks.length - 10 }} 项，去上传页看全部
      </div>
    </div>

    <div class="upload-panel__foot">
      <el-button size="small" link type="primary" @click="router.push('/upload')">查看上传页</el-button>
      <el-button v-if="store.activeCount > 0" size="small" link type="danger" @click="store.cancelAll()">
        全部取消
      </el-button>
      <el-button v-else size="small" link @click="store.clearFinished()">清除记录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUploadStore, type UploadTask } from '@/stores/upload'
import { formatDuration, formatSpeed } from '@/utils/format'

const router = useRouter()
const store = useUploadStore()
const expanded = ref(false)

function statusText(task: UploadTask) {
  switch (task.status) {
    case 'pending':
      return '排队中'
    case 'uploading':
      return task.progress + '%'
    case 'success':
      return '完成'
    case 'failed':
      return '失败'
    default:
      return '已取消'
  }
}
</script>

<style scoped>
/* 固定右下角，挂在布局上，所以切路由也在 */
.upload-panel {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 2100;
  width: 300px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 12px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.1);
  font-size: 12px;
}

.upload-panel__head {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.upload-panel__icon {
  color: var(--el-color-primary);
}

.upload-panel__title {
  font-weight: 600;
  font-size: 13px;
}

.upload-panel__meta {
  color: #909399;
  font-size: 11px;
}

.upload-panel__list {
  max-height: 180px;
  overflow: auto;
  margin-top: 6px;
  border-top: 1px solid #f0f2f5;
  padding-top: 6px;
}

.upload-panel__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}

.upload-panel__name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.upload-panel__state {
  flex: 0 0 auto;
  color: #909399;
}

.upload-panel__state.is-uploading {
  color: var(--el-color-primary);
}

.upload-panel__state.is-success {
  color: var(--el-color-success);
}

.upload-panel__state.is-failed {
  color: var(--el-color-danger);
}

.upload-panel__more {
  padding-top: 4px;
}

.upload-panel__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}
</style>
