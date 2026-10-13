<template>
  <el-dialog v-model="visible" title="批量迁移" width="720px" top="7vh" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
      <template #title>
        迁移会把图片从原位置下载后写入目标位置，并更新图库记录。可以只改目录前缀（同一存储内搬目录），
        也可以整个搬到另一个存储后端。任务在后台跑，没有数量上限，关闭弹窗不影响执行。
      </template>
    </el-alert>

    <el-form label-width="130px" :disabled="running">
      <el-form-item label="迁移范围">
        <span v-if="imageIds.length > 0">
          已勾选的 <b>{{ imageIds.length }}</b> 张图片
        </span>
        <span v-else>
          当前筛选条件下的全部 <b>{{ filterTotal }}</b> 张图片
          <el-checkbox v-model="migrateAll" style="margin-left: 12px">确认迁移全部</el-checkbox>
        </span>
      </el-form-item>

      <el-form-item label="目标存储后端">
        <el-select v-model="form.targetStorageConfigId" style="width: 100%" @change="onTargetChange">
          <el-option
            v-for="item in configs"
            :key="item.id"
            :label="item.name + ' · ' + item.typeLabel"
            :value="item.id"
            :disabled="!item.enabled"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="目标路径前缀">
        <el-input v-model="form.targetPrefix" placeholder="留空 = 沿用该存储配置的路径前缀" clearable />
      </el-form-item>

      <el-form-item label="命名方式">
        <el-checkbox v-model="form.preserveDirectory">保留原目录结构</el-checkbox>
        <el-checkbox v-model="form.preserveFileName" style="margin-left: 16px">保留原文件名</el-checkbox>
        <div class="text-muted" style="margin-top: 6px">
          目标对象键将形如：<span class="mono">{{ keyPreview }}</span>
        </div>
        <div v-if="form.preserveFileName" class="text-muted" style="margin-top: 4px">
          保留文件名时如果重名，会自动追加 -1、-2 序号，不会覆盖已有文件
        </div>
      </el-form-item>

      <el-form-item label="其它选项">
        <el-checkbox v-model="form.deleteSource">迁移成功后删除源文件</el-checkbox>
        <div v-if="form.deleteSource" class="text-muted" style="margin-top: 4px">
          仅在跨存储迁移时删除源文件；同存储同路径会被跳过，不会误删
        </div>
      </el-form-item>
    </el-form>

    <div v-if="task" class="progress">
      <el-progress
        :percentage="task.percent"
        :status="progressStatus"
        :stroke-width="14"
        striped
        :striped-flow="running"
      />
      <div class="progress__line">
        <span>状态：{{ statusText }}</span>
        <span>成功 {{ task.succeeded }}</span>
        <span>失败 {{ task.failed }}</span>
        <span>跳过 {{ task.skipped }}</span>
        <span>进度 {{ task.done }} / {{ task.total }}</span>
      </div>
      <div v-if="task.currentFile" class="text-muted">正在处理：{{ task.currentFile }}</div>
      <div class="text-muted" style="margin-top: 6px">
        任务在后台跑，可以关掉本窗口；进度随时在工具栏的「迁移任务」面板里查看。
      </div>
      <el-alert
        v-if="task.errors && task.errors.length > 0"
        type="warning"
        :closable="false"
        style="margin-top: 10px"
        :title="'失败 ' + task.failed + ' 张，前 ' + task.errors.length + ' 条原因：'"
      >
        <div v-for="(msg, index) in task.errors" :key="index" class="mono">{{ msg }}</div>
      </el-alert>
    </div>

    <template #footer>
      <el-button v-if="running" @click="doCancel">取消迁移</el-button>
      <el-button @click="visible = false">{{ running ? '后台继续' : '关闭' }}</el-button>
      <el-button
        v-if="!task || !running"
        type="primary"
        :loading="starting"
        :disabled="!canStart"
        @click="doStart"
      >
        {{ task ? '重新迁移' : '开始迁移' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { cancelMigrate, fetchMigrateStatus, startMigrate } from '@/api/images'
import { fetchStorageConfigs } from '@/api/storage'
import type { MigrationTaskVO, StorageConfigVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  imageIds: number[]
  filterStorageConfigId?: number
  filterKeyword?: string
  filterTotal: number
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'finished'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const configs = ref<StorageConfigVO[]>([])
const task = ref<MigrationTaskVO | null>(null)
const starting = ref(false)
const migrateAll = ref(false)
let timer: number | undefined

const form = reactive({
  targetStorageConfigId: undefined as number | undefined,
  targetPrefix: '',
  preserveDirectory: false,
  preserveFileName: false,
  deleteSource: false,
})

/** 让用户直观看到四种组合会生成什么样的对象键 */
const keyPreview = computed(() => {
  const prefix = form.targetPrefix ? form.targetPrefix.replace(/\/+$/, '') + '/' : '（目标前缀）/'
  const dir = form.preserveDirectory ? '2026/01/01/001/' : '日期/分片/'
  const name = form.preserveFileName ? 'photo.png' : 'a1b2c3d4….png'
  return prefix + dir + name
})

const running = computed(() => {
  const status = task.value ? task.value.status : ''
  return status === 'PENDING' || status === 'RUNNING'
})

const canStart = computed(() => {
  if (!form.targetStorageConfigId) {
    return false
  }
  return props.imageIds.length > 0 || migrateAll.value
})

const progressStatus = computed(() => {
  const status = task.value ? task.value.status : ''
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED' || status === 'PARTIAL') {
    return 'warning'
  }
  return undefined
})

const statusText = computed(() => {
  const map: Record<string, string> = {
    PENDING: '排队中',
    RUNNING: '迁移中',
    SUCCESS: '全部完成',
    PARTIAL: '部分完成（有失败）',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  const status = task.value ? task.value.status : ''
  return map[status] || status
})

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) {
      return
    }
    task.value = null
    migrateAll.value = false
    try {
      configs.value = (await fetchStorageConfigs()).data
      const first = configs.value.find((item) => item.enabled)
      if (first) {
        form.targetStorageConfigId = first.id
        applyPrefix(first)
      }
    } catch {
      // 拦截器已提示
    }
  },
)

watch(() => props.modelValue, (open) => { if (!open) { stopPolling() } })

onUnmounted(() => stopPolling())

function onTargetChange() {
  const config = configs.value.find((item) => item.id === form.targetStorageConfigId)
  if (config) {
    applyPrefix(config)
  }
}

function applyPrefix(config: StorageConfigVO) {
  form.targetPrefix = (config.config && config.config.pathPrefix) || ''
}

async function doStart() {
  if (!form.targetStorageConfigId) {
    return
  }
  starting.value = true
  try {
    const result = await startMigrate({
      targetStorageConfigId: form.targetStorageConfigId,
      targetPrefix: form.targetPrefix || undefined,
      preserveDirectory: form.preserveDirectory,
      preserveFileName: form.preserveFileName,
      deleteSource: form.deleteSource,
      imageIds: props.imageIds.length > 0 ? props.imageIds : undefined,
      sourceStorageConfigId: props.imageIds.length > 0 ? undefined : props.filterStorageConfigId,
      keyword: props.imageIds.length > 0 ? undefined : props.filterKeyword,
      migrateAll: props.imageIds.length > 0 ? undefined : migrateAll.value,
    })
    task.value = result.data
    ElMessage.success('迁移任务已启动，共 ' + result.data.total + ' 张')
    startPolling(result.data.taskId)
  } catch {
    // 拦截器已提示
  } finally {
    starting.value = false
  }
}

function startPolling(taskId: string) {
  stopPolling()
  timer = window.setInterval(async () => {
    try {
      const result = await fetchMigrateStatus(taskId)
      task.value = result.data
      if (!running.value) {
        stopPolling()
        emit('finished')
        if (result.data.status === 'SUCCESS') {
          ElMessage.success('迁移完成，成功 ' + result.data.succeeded + ' 张')
        } else if (result.data.status === 'CANCELLED') {
          ElMessage.warning('迁移已取消')
        } else {
          ElMessage.warning('迁移结束：成功 ' + result.data.succeeded + '，失败 ' + result.data.failed)
        }
      }
    } catch {
      stopPolling()
    }
  }, 1500)
}

function stopPolling() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function doCancel() {
  if (!task.value) {
    return
  }
  try {
    await cancelMigrate(task.value.taskId)
    ElMessage.info('已请求取消，正在处理的这一张会先完成')
  } catch {
    // 拦截器已提示
  }
}
</script>

<style scoped>
.progress {
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}

.progress__line {
  display: flex;
  gap: 18px;
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}
</style>
