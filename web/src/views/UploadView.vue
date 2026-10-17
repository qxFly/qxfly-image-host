<template>
  <div class="page">
    <h2 class="page-title">上传图片</h2>

    <el-card shadow="never" style="margin-bottom: 16px">
      <div class="toolbar">
        <span>目标存储</span>
        <el-select v-model="selectedConfigId" placeholder="使用默认存储后端" clearable style="width: 260px">
          <el-option
            v-for="item in configs"
            :key="item.id"
            :label="item.name + (item.isDefault ? '（默认）' : '')"
            :value="item.id"
            :disabled="!item.enabled"
          />
        </el-select>
        <span class="text-muted">
          文件一添加就进入下面的上传列表；上传跑在会话里，切到别的页面也不会中断
        </span>
      </div>

      <el-upload
        ref="uploadRef"
        drag
        multiple
        :show-file-list="false"
        :auto-upload="false"
        :on-change="onFileChange"
        :accept="accept"
      >
        <el-icon class="el-icon--upload" aria-hidden="true"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽图片到此处，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 {{ extensions.join(' / ') }}，单张{{ maxSizeText }}；
            也可以直接 Ctrl+V 粘贴剪贴板里的图片
          </div>
        </template>
      </el-upload>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>上传列表</span>
          <span class="text-muted">
            进行中 {{ store.activeCount }} 张 · 共 {{ store.tasks.length }} 条
            <template v-if="store.totalSpeed > 0">（{{ formatSpeed(store.totalSpeed) }}）</template>
          </span>
          <div class="toolbar-spacer"></div>
          <el-button :disabled="store.successTasks.length === 0" @click="copyAll('url', '全部直链')">
            复制全部直链
          </el-button>
          <el-button :disabled="store.successTasks.length === 0" @click="copyAll('markdown', '全部 Markdown')">
            复制全部 Markdown
          </el-button>
          <el-button :disabled="store.tasks.length === 0" @click="store.clearFinished()">清空已结束</el-button>
        </div>
      </template>

      <EmptyState
        v-if="store.tasks.length === 0"
        icon="UploadFilled"
        title="还没有上传记录"
        description="拖拽文件到上面即可开始；上传会实时显示进度、速度与预计剩余时间" />
      <TableCardList
        v-else-if="isMobile"
        :rows="store.tasks"
        :fields="cardFields"
        :id-key="'id'"
        empty-text="还没有上传记录"
        empty-description="拖拽文件到上面即可开始"
        empty-icon="UploadFilled"
      >
        <template #field-name="{ row }">
          <span>{{ row.name }}</span>
          <span class="text-muted">　{{ formatSize(row.size) }}</span>
        </template>
        <template #field-status="{ row }">
          <el-tooltip :disabled="!row.error" :content="row.error || ''" placement="top">
            <el-tag size="small" effect="plain" :type="statusTag(row)">{{ statusText(row) }}</el-tag>
          </el-tooltip>
        </template>
        <template #field-progress="{ row }">
          <el-progress
            :percentage="row.progress"
            :status="row.status === 'failed' ? 'exception' : row.status === 'success' ? 'success' : undefined"
            :stroke-width="8" />
        </template>
        <template #field-speed="{ row }">{{ formatSpeed(row.speed) }}</template>
        <template #field-eta="{ row }">{{ row.status === 'uploading' ? formatDuration(row.eta) : '—' }}</template>
        <template #field-storage="{ row }">
          <el-tag v-if="row.storageTypeLabel" size="small" effect="plain">{{ row.storageTypeLabel }}</el-tag>
          <span v-else class="text-muted">—</span>
        </template>
        <template #actions="{ row }">
          <template v-if="row.status === 'pending' || row.status === 'uploading'">
            <el-button size="small" link type="danger" @click="store.cancel(row.id)">取消</el-button>
          </template>
          <template v-else-if="row.status === 'failed' || row.status === 'canceled'">
            <el-button size="small" link type="primary" :disabled="!store.canRetry(row.id)" @click="store.retry(row.id)">重试</el-button>
          </template>
          <template v-else>
            <el-button size="small" link type="primary" @click="copy(row.copyUrl || row.url, '图片直链')">直链</el-button>
            <el-button size="small" link type="primary" @click="copy(markdownOf(row), 'Markdown')">MD</el-button>
            <el-button size="small" link type="success" @click="open(row.previewUrl || row.url)">打开</el-button>
          </template>
          <el-button size="small" link @click="store.remove(row.id)">移除</el-button>
        </template>
      </TableCardList>

      <el-table v-else :data="store.tasks" size="small" style="width: 100%">
        <el-table-column label="文件" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <span class="text-muted">　{{ formatSize(row.size) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip :disabled="!row.error" :content="row.error || ''" placement="top">
              <el-tag size="small" effect="plain" :type="statusTag(row)">{{ statusText(row) }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="180">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress"
              :status="row.status === 'failed' ? 'exception' : row.status === 'success' ? 'success' : undefined"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column label="速度" width="110">
          <template #default="{ row }">{{ formatSpeed(row.speed) }}</template>
        </el-table-column>
        <el-table-column label="剩余" width="110">
          <template #default="{ row }">
            {{ row.status === 'uploading' ? formatDuration(row.eta) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="存储" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.storageTypeLabel" size="small" effect="plain">{{ row.storageTypeLabel }}</el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" :fixed="isMobile ? undefined : 'right'">
          <template #default="{ row }">
            <template v-if="row.status === 'pending' || row.status === 'uploading'">
              <el-button size="small" link type="danger" @click="store.cancel(row.id)">取消</el-button>
            </template>
            <template v-else-if="row.status === 'failed' || row.status === 'canceled'">
              <el-button
                size="small"
                link
                type="primary"
                :disabled="!store.canRetry(row.id)"
                @click="store.retry(row.id)"
              >
                重试
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" link type="primary" @click="copy(row.copyUrl || row.url, '图片直链')">
                直链
              </el-button>
              <el-button size="small" link type="primary" @click="copy(markdownOf(row), 'Markdown')">MD</el-button>
              <el-button size="small" link type="success" @click="open(row.previewUrl || row.url)">打开</el-button>
            </template>
            <el-button size="small" link @click="store.remove(row.id)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useIsMobile } from '@/utils/responsive'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { fetchStorageConfigs } from '@/api/storage'
import { fetchSystemInfo } from '@/api/system'
import EmptyState from '@/components/EmptyState.vue'
import TableCardList from '@/components/TableCardList.vue'
import { useUploadStore, type UploadTask } from '@/stores/upload'
import { copyText, formatDuration, formatSize, formatSpeed } from '@/utils/format'
import type { StorageConfigVO } from '@/types'

const store = useUploadStore()
const isMobile = useIsMobile()

/** 手机端卡片模式字段 */
const cardFields = [
  { label: '文件', prop: 'name' },
  { label: '状态', prop: 'status' },
  { label: '进度', prop: 'progress' },
  { label: '速度', prop: 'speed' },
  { label: '剩余', prop: 'eta' },
  { label: '存储', prop: 'storageTypeLabel' },
]
const uploadRef = ref<UploadInstance>()
const configs = ref<StorageConfigVO[]>([])
const selectedConfigId = ref<number | undefined>(undefined)
/** 单张大小上限（字节）；-1 表示无上限（在「系统设置」里改） */
const maxSize = ref(30 * 1024 * 1024)
const maxSizeText = computed(() =>
  maxSize.value > 0 ? '不超过 ' + formatSize(maxSize.value) : '不限大小',
)
const extensions = ref<string[]>(['jpg', 'jpeg', 'png', 'gif', 'webp'])

const accept = computed(() => extensions.value.map((ext) => '.' + ext).join(','))

onMounted(async () => {
  try {
    configs.value = (await fetchStorageConfigs()).data
    const preset = configs.value.find((item) => item.isDefault && item.enabled)
    if (preset) {
      selectedConfigId.value = preset.id
    }
  } catch {
    // 忽略
  }
  try {
    const info = (await fetchSystemInfo()).data
    maxSize.value = info.maxUploadSize
    if (info.allowedExtensions.length) {
      extensions.value = info.allowedExtensions
    }
  } catch {
    // 忽略
  }
  document.addEventListener('paste', onPaste)
  window.addEventListener('beforeunload', store.flush)
})

// UploadView 是路由组件，切走会卸载；但上传本身在 store 里，不受影响
onUnmounted(() => {
  document.removeEventListener('paste', onPaste)
  window.removeEventListener('beforeunload', store.flush)
})

/** 超过上限的直接拦下，免得白传一遍才被服务端拒绝 */
function oversize(file: File) {
  return maxSize.value > 0 && file.size > maxSize.value
}

/** 选完文件立刻进列表并开始上传 */
function onFileChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (raw) {
    if (oversize(raw)) {
      ElMessage.error(raw.name + ' 为 ' + formatSize(raw.size) + '，超过单张上限 ' + formatSize(maxSize.value))
    } else {
      const count = store.addFiles([raw], selectedConfigId.value)
      if (count > 0) {
        ElMessage.success('已加入上传列表：' + raw.name)
      }
    }
  }
  uploadRef.value?.clearFiles()
}

function onPaste(event: ClipboardEvent) {
  const items = event.clipboardData ? event.clipboardData.items : null
  if (!items) {
    return
  }
  const pasted: File[] = []
  for (const item of Array.from(items)) {
    if (item.type.indexOf('image') === 0) {
      const file = item.getAsFile()
      if (file && !oversize(file)) {
        pasted.push(file)
      }
    }
  }
  if (pasted.length > 0) {
    store.addFiles(pasted, selectedConfigId.value)
    ElMessage.info('检测到剪贴板图片，已加入上传列表')
  }
}

/** el-table 插槽行是宽松类型 */
interface TaskRow {
  status?: UploadTask['status']
  name?: string
  url?: string
  previewUrl?: string
  copyUrl?: string
  markdown?: string
}

function statusText(task: TaskRow) {
  switch (task.status) {
    case 'pending':
      return '排队中'
    case 'uploading':
      return '上传中'
    case 'success':
      return '完成'
    case 'failed':
      return '失败'
    default:
      return '已取消'
  }
}

function statusTag(task: TaskRow) {
  switch (task.status) {
    case 'success':
      return 'success'
    case 'failed':
      return 'danger'
    case 'uploading':
      return 'primary'
    default:
      return 'info'
  }
}

async function copy(text?: string, label?: string) {
  if (!text) {
    return
  }
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success((label || '内容') + '已复制')
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

/** Markdown 用「复制默认线路」的地址拼，跟 CDN 设置保持一致 */
function markdownOf(task: TaskRow) {
  if (task.copyUrl) {
    return '![' + (task.name || '') + '](' + task.copyUrl + ')'
  }
  return task.markdown
}

async function copyAll(field: 'url' | 'markdown', label: string) {
  const text = store.successTasks
    .map((task) => (field === 'url' ? task.copyUrl || task.url : markdownOf(task)))
    .filter(Boolean)
    .join('\n')
  await copy(text, label)
}

function open(url?: string) {
  if (url) {
    window.open(url, '_blank')
  }
}
</script>
