<template>
  <el-dialog v-model="visible" title="从存储导入已有图片" width="900px" top="6vh" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
      <template #title>
        扫描存储后端里已经存在、但平台还没登记的文件。导入只登记元数据，不会重新下载或上传文件。
        路径前缀留空表示从根目录扫全库。目前支持 GitHub 与 S3 兼容存储（含 R2 / MinIO）。
      </template>
    </el-alert>

    <div class="toolbar">
      <el-select v-model="configId" placeholder="选择存储后端" style="width: 240px">
        <el-option
          v-for="item in importable"
          :key="item.id"
          :label="item.name + ' · ' + item.typeLabel"
          :value="item.id"
        />
      </el-select>
      <el-input v-model="prefix" placeholder="路径前缀，留空 = 全库" style="width: 220px" clearable />
      <el-button type="primary" :loading="scanning" :disabled="!configId" @click="scan(1)">扫描</el-button>
      <div class="toolbar-spacer"></div>
      <span v-if="summary" class="text-muted">{{ summary }}</span>
    </div>

    <el-alert
      v-if="preview && preview.truncated"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="远端文件数已达到扫描上限，可能未拉全。可调大后端环境变量 IMPORT_MAX_FILES 后重试。"
    />

    <el-table
      ref="tableRef"
      v-loading="scanning"
      :data="preview ? preview.candidates : []"
      height="340"
      size="small"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="46" :selectable="selectable" />
      <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.imported ? 'info' : 'success'" effect="plain">
            {{ row.imported ? '已导入' : '待导入' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="objectKey" label="对象键" min-width="280" show-overflow-tooltip />
      <template #empty>
        <el-empty :description="preview ? '该前缀下没有可导入的图片' : '先选择存储后端并点击扫描'" :image-size="70" />
      </template>
    </el-table>

    <div v-if="preview && preview.totalPages > 1" class="pager">
      <el-pagination
        :current-page="preview.page"
        :page-size="preview.size"
        :total="preview.scanned"
        :pager-count="5"
        layout="total, prev, pager, next"
        background
        small
        @current-change="scan"
      />
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button :disabled="pendingOnPage.length === 0" @click="selectAllPending">全选本页待导入</el-button>
      <el-button :loading="importing" :disabled="selection.length === 0" @click="doImport(false)">
        导入选中 ({{ selection.length }})
      </el-button>
      <el-button
        v-if="preview && preview.importable > 0"
        type="primary"
        :loading="importing"
        @click="doImport(true)"
      >
        全部导入 ({{ preview.importable }})
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, type TableInstance } from 'element-plus'
import { importFromStorage, previewImport } from '@/api/images'
import { fetchStorageConfigs } from '@/api/storage'
import { formatSize } from '@/utils/format'
import type { ImportCandidateVO, ImportPreviewVO, StorageConfigVO } from '@/types'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'imported'): void
}>()

const MAX_PAGE_SIZE = 200

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const configs = ref<StorageConfigVO[]>([])
const tableRef = ref<TableInstance>()
const configId = ref<number | undefined>(undefined)
const prefix = ref('images')
const preview = ref<ImportPreviewVO | null>(null)
const selection = ref<ImportCandidateVO[]>([])
const scanning = ref(false)
const importing = ref(false)

const importable = computed(() => configs.value.filter((item) => item.supportsList && item.enabled))
const pendingOnPage = computed(() =>
  preview.value ? preview.value.candidates.filter((item) => !item.imported) : [],
)
const summary = computed(() => {
  if (!preview.value) {
    return ''
  }
  return '扫描到 ' + preview.value.scanned + ' 个图片，待导入 ' + preview.value.importable
    + ' 个，已导入 ' + preview.value.imported + ' 个'
})

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) {
      return
    }
    preview.value = null
    selection.value = []
    try {
      configs.value = (await fetchStorageConfigs()).data
      const first = importable.value[0]
      if (first) {
        configId.value = first.id
        applyPrefix(first)
      }
    } catch {
      // 拦截器已提示
    }
  },
)

watch(configId, (id) => {
  const config = configs.value.find((item) => item.id === id)
  if (config) {
    applyPrefix(config)
  }
})

function applyPrefix(config: StorageConfigVO) {
  prefix.value = (config.config && config.config.pathPrefix) || ''
}

function selectable(row: ImportCandidateVO) {
  return !row.imported
}

function onSelectionChange(rows: ImportCandidateVO[]) {
  selection.value = rows
}

async function scan(page: number) {
  if (!configId.value) {
    return
  }
  scanning.value = true
  try {
    const result = await previewImport(configId.value, prefix.value || undefined, page, MAX_PAGE_SIZE)
    preview.value = result.data
    selection.value = []
    await nextTick()
    selectAllPending()
  } catch {
    preview.value = null
  } finally {
    scanning.value = false
  }
}

function selectAllPending() {
  const table = tableRef.value
  if (!table) {
    return
  }
  table.clearSelection()
  pendingOnPage.value.forEach((row) => table.toggleRowSelection(row, true))
}

async function doImport(all: boolean) {
  if (!configId.value) {
    return
  }
  if (!all && selection.value.length === 0) {
    return
  }
  importing.value = true
  try {
    const result = await importFromStorage({
      storageConfigId: configId.value,
      prefix: prefix.value || undefined,
      // 全部导入时不传 objectKeys，由服务端自己遍历，不受分页影响
      objectKeys: all ? undefined : selection.value.map((item) => item.objectKey),
    })
    ElMessage.success(
      '导入完成：新增 ' + result.data.imported + ' 个，跳过 ' + result.data.skipped + ' 个（已存在）',
    )
    emit('imported')
    await scan(preview.value ? preview.value.page : 1)
  } catch {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.pager {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
</style>
