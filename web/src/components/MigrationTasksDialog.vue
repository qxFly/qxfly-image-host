<template>
  <el-dialog v-model="visible" title="后台迁移任务" width="1000px" top="6vh" :close-on-click-modal="false">
    <div class="toolbar" style="margin-bottom: 12px">
      <span class="text-muted">
        迁移在后台运行，关掉窗口或刷新页面都不影响。任务记录保存在内存里，服务重启后列表会清空（已迁移的图片不受影响）。
      </span>
      <div class="toolbar-spacer"></div>
      <el-tag v-if="runningCount > 0" type="warning" effect="plain">进行中 {{ runningCount }}</el-tag>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <!-- 失败项：持久化保存，重启不丢，可一键重试（F5） -->
    <div v-if="failures.length > 0" class="failure-panel">
      <div class="failure-panel__head">
        <span>失败项（已持久化，重启不丢）</span>
        <el-tag size="small" type="danger" effect="plain">{{ failures.length }} 条</el-tag>
        <div class="toolbar-spacer"></div>
        <el-button size="small" type="primary" :loading="retrying" @click="retryFailures">一键重试</el-button>
      </div>
      <div v-for="item in failures" :key="item.imageId + '-' + item.toConfigId" class="failure-item">
        <span class="failure-item__name" :title="item.originalName">{{ item.originalName }}</span>
        <span class="text-muted">{{ item.reason || '未知原因' }}</span>
        <span class="text-muted">{{ item.createdAt }}</span>
      </div>
    </div>

    <el-table v-loading="loading" :data="tasks" size="small" max-height="440" row-key="taskId">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div><span class="text-muted">任务 ID：</span><span class="mono">{{ row.taskId }}</span></div>
            <div><span class="text-muted">并发数：</span>{{ row.concurrency }}</div>
            <div v-if="row.currentFile"><span class="text-muted">当前文件：</span>{{ row.currentFile }}</div>
            <div v-if="row.message"><span class="text-muted">说明：</span>{{ row.message }}</div>
            <div v-if="row.errors && row.errors.length" style="margin-top: 8px">
              <span class="text-muted">
                失败原因：共 {{ row.failed }} 张失败，归并为 {{ row.errors.length }} 类
              </span>
              <div v-for="(item, index) in row.errors" :key="index" class="error-group">
                <div class="error-group__head">
                  <el-tag size="small" type="danger" effect="plain">{{ item.count }} 张</el-tag>
                  <span class="error-group__reason">{{ item.reason }}</span>
                </div>
                <div v-if="item.samples && item.samples.length" class="error-group__samples mono">
                  示例：{{ item.samples.join('、') }}{{ item.count > item.samples.length ? ' …' : '' }}
                </div>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="迁移方向" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.sourceName }}</span>
          <el-icon style="margin: 0 4px"><Right /></el-icon>
          <span>{{ row.targetName }}</span>
        </template>
      </el-table-column>

      <el-table-column label="进度" min-width="230">
        <template #default="{ row }">
          <el-progress
            :percentage="row.percent"
            :stroke-width="12"
            :status="progressStatus(row.status)"
            :striped="isRunning(row.status)"
            :striped-flow="isRunning(row.status)"
          />
          <div class="text-muted" style="margin-top: 2px">{{ row.done }} / {{ row.total }} 张</div>
        </template>
      </el-table-column>

      <el-table-column label="结果" width="170">
        <template #default="{ row }">
          <span class="ok">成功 {{ row.succeeded }}</span>
          <span v-if="row.failed > 0" class="fail">　失败 {{ row.failed }}</span>
          <span v-if="row.skipped > 0" class="text-muted">　跳过 {{ row.skipped }}</span>
        </template>
      </el-table-column>

      <el-table-column label="耗时" width="110">
        <template #default="{ row }">{{ duration(row) }}</template>
      </el-table-column>

      <el-table-column label="开始时间" width="150">
        <template #default="{ row }">{{ formatDate(new Date(row.startedAt).toISOString()) }}</template>
      </el-table-column>

      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="isRunning(row.status)"
            size="small"
            link
            type="danger"
            @click="doCancel(row)"
          >
            取消
          </el-button>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="还没有迁移任务" :image-size="70" />
      </template>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelMigrate, fetchMigrationFailures, fetchMigrateTasks, retryMigrationFailures } from '@/api/images'
import { formatDate } from '@/utils/format'
import type { MigrationTaskVO } from '@/types'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const tasks = ref<MigrationTaskVO[]>([])

/** 已持久化的失败项（重启不丢），有失败时才显示 */
interface MigrationFailureItem {
  imageId: number
  originalName: string
  toConfigId: number
  reason?: string
  createdAt: string
}

const failures = ref<MigrationFailureItem[]>([])
const retrying = ref(false)
const loading = ref(false)
let timer: number | undefined

const runningCount = computed(() => tasks.value.filter((item) => isRunning(item.status)).length)

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      load()
      startPolling()
    } else {
      stopPolling()
    }
  },
)

onUnmounted(() => stopPolling())

function isRunning(status: string) {
  return status === 'PENDING' || status === 'RUNNING'
}

async function load() {
  loading.value = true
  try {
    tasks.value = (await fetchMigrateTasks()).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
  try {
    failures.value = (await fetchMigrationFailures()).data
  } catch {
    // 拿不到失败项不影响任务列表
  }
}

/** 一键重试：把持久化的失败项重新提交成一个迁移任务 */
async function retryFailures() {
  const confirmed = await ElMessageBox.confirm(
    '将把 ' + failures.value.length + ' 条失败记录重新提交为一个新的迁移任务（按原目标迁移）。继续？',
    '重试失败项',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  retrying.value = true
  try {
    await retryMigrationFailures()
    ElMessage.success('已提交重试任务，进度见任务列表')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    retrying.value = false
  }
}

function startPolling() {
  stopPolling()
  timer = window.setInterval(load, 2000)
}

function stopPolling() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function doCancel(row: any) {
  try {
    await cancelMigrate(row.taskId)
    ElMessage.info('已请求取消')
    await load()
  } catch {
    // 拦截器已提示
  }
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '排队中',
    RUNNING: '迁移中',
    SUCCESS: '完成',
    PARTIAL: '部分失败',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return map[status] || status
}

function statusType(status: string) {
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'RUNNING' || status === 'PENDING') {
    return 'warning'
  }
  if (status === 'PARTIAL') {
    return 'warning'
  }
  if (status === 'FAILED') {
    return 'danger'
  }
  return 'info'
}

function progressStatus(status: string) {
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED' || status === 'PARTIAL') {
    return 'warning'
  }
  return undefined
}

function duration(row: any) {
  const end = row.finishedAt || Date.now()
  const ms = Math.max(0, end - row.startedAt)
  if (ms < 1000) {
    return ms + ' ms'
  }
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) {
    return seconds + ' 秒'
  }
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  if (minutes < 60) {
    return minutes + ' 分 ' + rest + ' 秒'
  }
  return Math.floor(minutes / 60) + ' 时 ' + (minutes % 60) + ' 分'
}
</script>

<style scoped>
.detail {
  padding: 4px 14px 8px;
  font-size: 13px;
  line-height: 1.9;
}

.error-group {
  margin-top: 6px;
  padding: 6px 10px;
  border-left: 3px solid #f56c6c;
  background: #fef0f0;
  border-radius: 0 4px 4px 0;
}

.error-group__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-group__reason {
  font-size: 13px;
  color: #f56c6c;
  word-break: break-all;
}

.error-group__samples {
  margin-top: 3px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}

.ok {
  color: #67c23a;
}

.fail {
  color: #f56c6c;
}

/* 迁移失败项面板（F5） */
.failure-panel {
  border: var(--jf-hair, 1px solid rgba(212, 212, 207, 0.4));
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: #fdf7f8;
}

.failure-panel__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}

.failure-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  padding: 3px 0;
}

.failure-item__name {
  flex: 0 0 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 820px) {
  .failure-item {
    flex-wrap: wrap;
  }

  .failure-item__name {
    flex: 0 0 100%;
  }
}

</style>
