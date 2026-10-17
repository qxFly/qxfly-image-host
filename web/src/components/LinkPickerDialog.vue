<template>
  <el-dialog v-model="visible" :title="title" width="840px" top="8vh" @open="onOpen">
    <div class="link-head">
      <el-image
        class="link-thumb"
        :src="thumb"
        :alt="image && image.originalName ? image.originalName : '图片'"
        fit="cover"
        :preview-src-list="image ? [image.previewUrl || image.url] : []"
        preview-teleported
        hide-on-click-modal
      >
        <template #error>
          <div class="link-thumb link-thumb--empty"><el-icon><Picture /></el-icon></div>
        </template>
      </el-image>
      <div class="link-info">
        <div class="link-file">{{ image ? image.originalName : '' }}</div>
        <div class="text-muted">{{ purposeHint }}</div>
      </div>
      <div class="toolbar-spacer"></div>
      <el-button size="small" :loading="testing" @click="testAll">
        <el-icon><Odometer /></el-icon>&nbsp;重新测速
      </el-button>
    </div>

    <el-table v-loading="loading" :data="links" max-height="400" @row-click="onRowClick">
      <el-table-column width="44">
        <template #default="{ row }">
          <el-radio v-model="selectedKey" :value="row.key">&nbsp;</el-radio>
        </template>
      </el-table-column>
      <el-table-column label="线路" min-width="190">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag v-if="row.builtin && !row.origin && !row.proxy" size="small" effect="plain" class="ml">预设</el-tag>
          <el-tag v-if="isPurposeDefault(row)" size="small" type="success" effect="plain" class="ml">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="地址" min-width="250">
        <template #default="{ row }">
          <span v-if="row.proxy" class="text-muted">由服务端取回后回传，不暴露直链</span>
          <span v-else class="mono link-url" :title="row.url || ''">{{ row.url }}</span>
        </template>
      </el-table-column>
      <el-table-column label="延时" width="104">
        <template #default="{ row }">
          <span v-if="row.proxy" class="text-muted">—</span>
          <el-tag v-else size="small" effect="plain" :type="latencyTagType(latency[row.key])">
            {{ latencyText(row.key) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="!row.proxy" size="small" link type="primary" @click.stop="copyUrl(row)">复制</el-button>
          <el-button v-if="!row.proxy" size="small" link type="primary" @click.stop="copyMarkdown(row)">MD</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="working" @click="confirm">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadImage, fetchImageLinks } from '@/api/images'
import { fetchBlobUrl, saveBlob } from '@/utils/download'
import { copyText } from '@/utils/format'
import { latencyTagType, measureLatency } from '@/utils/latency'
import type { ImageLinkVO, ImageVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  image: ImageVO | null
  /** copy 复制链接 | markdown 复制 Markdown | download 下载 */
  mode: 'copy' | 'markdown' | 'download'
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const loading = ref(false)
const testing = ref(false)
const working = ref(false)
const links = ref<ImageLinkVO[]>([])
const selectedKey = ref('')
/** key -> 毫秒；undefined 表示正在测，null 表示不可达 */
const latency = ref<Record<string, number | null | undefined>>({})

const title = computed(() => {
  if (props.mode === 'markdown') {
    return '复制 Markdown'
  }
  return props.mode === 'download' ? '下载图片' : '复制链接'
})

const purposeHint = computed(() => {
  const base = '选择使用哪条线路；延时是本机到该线路的往返耗时，仅供参考'
  return props.mode === 'download' ? base + '。「原站代理下载」适合私有存储' : base
})

const confirmText = computed(() => {
  if (props.mode === 'markdown') {
    return '复制选中 Markdown'
  }
  return props.mode === 'download' ? '下载选中线路' : '复制选中链接'
})

const thumb = computed(() => {
  const image = props.image
  return image ? image.thumbUrl || image.previewUrl || image.url : ''
})

/** el-table 插槽行是宽松类型，用需要的字段做约束，省得每处都断言 */
interface LinkRow {
  key?: string
  name?: string
  url?: string | null
  markdown?: string | null
  origin?: boolean
  proxy?: boolean
  builtin?: boolean
  previewDefault?: boolean
  downloadDefault?: boolean
  copyDefault?: boolean
}

function isPurposeDefault(row: LinkRow) {
  return props.mode === 'download' ? row.downloadDefault : row.copyDefault
}

function latencyText(key: string) {
  const value = latency.value[key]
  if (value === undefined) {
    return '测速中…'
  }
  return value === null ? '不可达' : value + ' ms'
}

async function onOpen() {
  if (!props.image) {
    return
  }
  loading.value = true
  links.value = []
  latency.value = {}
  selectedKey.value = ''
  try {
    const result = await fetchImageLinks(props.image.id)
    links.value = result.data
    const preferred = result.data.find(isPurposeDefault)
    const fallback = result.data.find((item) => !item.proxy) || result.data[0]
    selectedKey.value = (preferred || fallback)?.key || ''
    loading.value = false
    await testAll()
  } catch {
    loading.value = false
  }
}

/** 逐个测：线路一般就几条，串行测不会互相抢带宽，结果也更稳 */
async function testAll() {
  testing.value = true
  latency.value = {}
  for (const link of links.value) {
    if (!link.url) {
      continue
    }
    latency.value = { ...latency.value, [link.key]: undefined }
    const ms = await measureLatency(link.url)
    latency.value = { ...latency.value, [link.key]: ms }
  }
  testing.value = false
}

async function testOne(row: ImageLinkVO) {
  if (!row.url) {
    return
  }
  latency.value = { ...latency.value, [row.key]: undefined }
  const ms = await measureLatency(row.url)
  latency.value = { ...latency.value, [row.key]: ms }
}

function onRowClick(row: LinkRow) {
  if (row.key) {
    selectedKey.value = row.key
  }
}

async function copyUrl(row: LinkRow) {
  if (!row.url) {
    return
  }
  const ok = await copyText(row.url)
  if (ok) {
    ElMessage.success('已复制链接：' + row.name)
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function copyMarkdown(row: LinkRow) {
  if (!row.markdown) {
    return
  }
  const ok = await copyText(row.markdown)
  if (ok) {
    ElMessage.success('已复制 Markdown：' + row.name)
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

const selected = computed(() => links.value.find((item) => item.key === selectedKey.value) || null)

async function doDownload(link: LinkRow) {
  if (!props.image) {
    return
  }
  const fileName = props.image.originalName || 'image'
  working.value = true
  try {
    if (link.proxy) {
      saveBlob(await downloadImage(props.image.id), fileName)
      return
    }
    try {
      saveBlob(await fetchBlobUrl(link.url || ''), fileName)
    } catch {
      // CDN 没开 CORS 时浏览器拿不到内容，退化成直接打开，让浏览器自己去下载
      window.open(link.url || '', '_blank')
      ElMessage.warning('该线路不允许跨域读取，已在新标签页打开')
    }
  } catch {
    // 拦截器已提示
  } finally {
    working.value = false
  }
}

async function confirm() {
  const link = selected.value
  if (!link) {
    ElMessage.warning('请先选择一条线路')
    return
  }
  if (props.mode === 'copy') {
    await copyUrl(link)
    visible.value = false
    return
  }
  if (props.mode === 'markdown') {
    await copyMarkdown(link)
    visible.value = false
    return
  }
  await doDownload(link)
}
</script>

<style scoped>
.link-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.link-thumb {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  background: #f0f2f5;
  flex: 0 0 auto;
}

.link-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.link-file {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.link-url {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.ml {
  margin-left: 6px;
}
</style>
