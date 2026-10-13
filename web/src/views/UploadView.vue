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
        <span class="text-muted">也可以直接 Ctrl+V 粘贴剪贴板中的图片</span>
      </div>

      <el-upload
        drag
        multiple
        :show-file-list="false"
        :http-request="customUpload"
        :accept="accept"
        :disabled="uploading"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽图片到此处，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 {{ extensions.join(' / ') }}，单张不超过 {{ formatSize(maxSize) }}
          </div>
        </template>
      </el-upload>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>本次会话已上传 {{ results.length }} 张</span>
          <div class="toolbar-spacer"></div>
          <el-button :disabled="results.length === 0" @click="copyAll('url', '全部直链')">复制全部直链</el-button>
          <el-button :disabled="results.length === 0" @click="copyAll('markdown', '全部 Markdown')">
            复制全部 Markdown
          </el-button>
          <el-button :disabled="results.length === 0" @click="results = []">清空</el-button>
        </div>
      </template>

      <el-empty v-if="results.length === 0" description="还没有上传记录" :image-size="70" />
      <el-table v-else :data="results" size="small" style="width: 100%">
        <el-table-column label="预览" width="80">
          <template #default="{ row }">
            <el-image :src="row.thumbUrl || row.url" fit="cover" style="width: 48px; height: 48px; border-radius: 4px" />
          </template>
        </el-table-column>
        <el-table-column prop="originalName" label="文件名" show-overflow-tooltip />
        <el-table-column label="存储" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.storageTypeLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="copy(row.url, '图片直链')">直链</el-button>
            <el-button size="small" link type="primary" @click="copy(row.markdown, 'Markdown')">MD</el-button>
            <el-button size="small" link type="success" @click="preview(row)">打开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadImage } from '@/api/images'
import { fetchStorageConfigs } from '@/api/storage'
import { fetchSystemInfo } from '@/api/system'
import { copyText, formatSize } from '@/utils/format'
import type { ImageVO, StorageConfigVO } from '@/types'

const configs = ref<StorageConfigVO[]>([])
const results = ref<ImageVO[]>([])
const selectedConfigId = ref<number | undefined>(undefined)
const uploading = ref(false)
const maxSize = ref(30 * 1024 * 1024)
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
})

onUnmounted(() => {
  document.removeEventListener('paste', onPaste)
})

async function customUpload(options: { file: File }) {
  uploading.value = true
  try {
    const result = await uploadImage(options.file, selectedConfigId.value)
    results.value.unshift(result.data)
    ElMessage.success('上传成功：' + result.data.originalName)
    return result.data
  } catch (error) {
    return Promise.reject(error)
  } finally {
    uploading.value = false
  }
}

function onPaste(event: ClipboardEvent) {
  const items = event.clipboardData ? event.clipboardData.items : null
  if (!items) {
    return
  }
  for (const item of Array.from(items)) {
    if (item.type.indexOf('image') === 0) {
      const file = item.getAsFile()
      if (file) {
        customUpload({ file })
        ElMessage.info('检测到剪贴板图片，开始上传')
      }
    }
  }
}

async function copy(text: string, label: string) {
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success(label + '已复制')
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function copyAll(field: 'url' | 'markdown', label: string) {
  const text = results.value
    .map((item) => (field === 'url' ? item.url : item.markdown))
    .filter(Boolean)
    .join('\n')
  await copy(text, label)
}

function preview(row: any) {
  window.open(row.url, '_blank')
}
</script>
