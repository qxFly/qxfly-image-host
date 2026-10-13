<template>
  <div class="page gallery">
    <div class="gallery-body">
      <el-card class="gallery-side" shadow="never">
        <div class="side-head">
          <span class="side-title">目录</span>
          <el-button link size="small" :loading="treeLoading" @click="loadTree">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
        <div
          class="side-all"
          :class="{ 'side-all--active': !query.directory }"
          @click="selectDirectory(undefined)"
        >
          全部（{{ dirTree.total }}）
        </div>
        <el-tree
          v-if="treeData.length"
          :key="treeVersion"
          ref="treeRef"
          :data="treeData"
          node-key="path"
          :props="{ label: 'label', children: 'children' }"
          :current-node-key="query.directory"
          :default-expanded-keys="expandedKeys"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-node__name">{{ data.label }}</span>
              <span class="tree-node__count">{{ data.totalCount }}</span>
            </span>
          </template>
        </el-tree>
        <el-empty v-else-if="!treeLoading" description="没有目录" :image-size="50" />
      </el-card>

      <div class="gallery-main">
        <div class="toolbar">
          <el-select v-model="query.matchMode" style="width: 100px" @change="load(1)">
            <el-option label="模糊" value="fuzzy" />
            <el-option label="精准" value="exact" />
            <el-option label="目录" value="prefix" />
          </el-select>
          <el-input
            v-model="query.keyword"
            :placeholder="searchPlaceholder"
            clearable
            style="width: 250px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select
            v-model="query.storageConfigId"
            placeholder="全部存储后端"
            clearable
            style="width: 180px"
            @change="onStorageChange"
          >
            <el-option v-for="item in configs" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <div class="toolbar-spacer"></div>
          <el-button @click="load()"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
          <el-button @click="importVisible = true">
            <el-icon><Download /></el-icon>&nbsp;从存储导入
          </el-button>
          <el-button :disabled="images.length === 0" @click="selectAllOnPage">
            {{ selectedIds.size === images.length && images.length > 0 ? '取消全选' : '全选本页' }}
          </el-button>
          <el-button v-if="selectedIds.size > 0" @click="clearSelection">
            清空选择 ({{ selectedIds.size }})
          </el-button>
          <el-button type="warning" plain @click="migrateVisible = true">
            <el-icon><Sort /></el-icon>&nbsp;批量迁移
          </el-button>
          <el-button @click="tasksVisible = true">
            <el-icon><List /></el-icon>&nbsp;迁移任务
          </el-button>
        </div>

        <div class="crumb">
          <span class="text-muted">当前位置：</span>
          <span class="mono">{{ query.directory === ROOT_DIRECTORY ? '（根目录）' : (query.directory || '全部') }}</span>
          <span class="text-muted">　共 {{ total }} 张</span>
          <el-button v-if="query.directory" link type="primary" size="small" @click="selectDirectory(undefined)">
            返回全部
          </el-button>
        </div>

        <el-empty v-if="!loading && images.length === 0" :description="emptyHint" />

        <div v-else v-loading="loading" class="image-grid">
          <ImageCard
            v-for="image in images"
            :key="image.id"
            :image="image"
            :selected="selectedIds.has(image.id)"
            @toggle="toggleSelect"
            @remove="remove"
            @copy="copy"
          />
        </div>

        <div class="pager">
          <div class="pager__size">
            <span class="text-muted">每页</span>
            <el-select v-model="sizeOption" style="width: 108px" @change="onSizeOptionChange">
              <el-option v-for="item in SIZE_OPTIONS" :key="item" :label="item + ' 条'" :value="item" />
              <el-option label="自定义" :value="CUSTOM_SIZE" />
            </el-select>
            <el-input-number
              v-if="sizeOption === CUSTOM_SIZE"
              v-model="customSize"
              :min="1"
              :max="MAX_PAGE_SIZE"
              :step="10"
              size="small"
              style="width: 120px"
              @change="onCustomSizeChange"
            />
            <span v-if="sizeOption === CUSTOM_SIZE" class="text-muted">1 - {{ MAX_PAGE_SIZE }}</span>
          </div>
          <el-pagination
            v-model:current-page="query.page"
            :page-size="query.size"
            :total="total"
            layout="total, prev, pager, next, jumper"
            background
            @current-change="load()"
          />
        </div>
      </div>
    </div>

    <ImportDialog v-model="importVisible" @imported="onDataChanged" />
    <MigrationTasksDialog v-model="tasksVisible" />
    <MigrateDialog
      v-model="migrateVisible"
      :image-ids="Array.from(selectedIds)"
      :filter-storage-config-id="query.storageConfigId"
      :filter-keyword="query.keyword"
      :filter-total="total"
      @finished="onDataChanged"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ImageCard from '@/components/ImageCard.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import MigrateDialog from '@/components/MigrateDialog.vue'
import MigrationTasksDialog from '@/components/MigrationTasksDialog.vue'
import { deleteImage, fetchDirectories, fetchImages } from '@/api/images'
import { fetchStorageConfigs } from '@/api/storage'
import { copyText } from '@/utils/format'
import type { DirectoryNodeVO, DirectoryTreeVO, ImageVO, StorageConfigVO } from '@/types'

/** 与后端 ImageService.ROOT_DIRECTORY 保持一致 */
const ROOT_DIRECTORY = '__root__'

const SIZE_OPTIONS = [30, 50, 100, 200]
const CUSTOM_SIZE = -1
const MAX_PAGE_SIZE = 500
const SIZE_STORAGE_KEY = 'pichost_page_size'

const router = useRouter()
const loading = ref(false)
const images = ref<ImageVO[]>([])
const configs = ref<StorageConfigVO[]>([])
const total = ref(0)

const importVisible = ref(false)
const migrateVisible = ref(false)
const tasksVisible = ref(false)
const selectedIds = ref<Set<number>>(new Set())

const treeRef = ref()
const treeLoading = ref(false)
const treeVersion = ref(0)
const dirTree = ref<DirectoryTreeVO>({ total: 0, rootFileCount: 0, nodes: [] })

interface TreeNode {
  path: string
  label: string
  totalCount: number
  children: TreeNode[]
}

function readStoredSize(): number {
  const stored = Number(localStorage.getItem(SIZE_STORAGE_KEY))
  if (!Number.isFinite(stored) || stored < 1) {
    return SIZE_OPTIONS[0]
  }
  return Math.min(Math.floor(stored), MAX_PAGE_SIZE)
}

const initialSize = readStoredSize()
const sizeOption = ref<number>(SIZE_OPTIONS.includes(initialSize) ? initialSize : CUSTOM_SIZE)
const customSize = ref<number>(initialSize)

const query = reactive<{
  page: number
  size: number
  keyword: string
  storageConfigId?: number
  directory?: string
  matchMode: string
}>({
  page: 1,
  size: initialSize,
  keyword: '',
  storageConfigId: undefined,
  directory: undefined,
  matchMode: 'fuzzy',
})

const searchPlaceholder = computed(() => {
  if (query.matchMode === 'exact') {
    return '完整对象键，或完整文件名'
  }
  if (query.matchMode === 'prefix') {
    return '目录路径，如 api/images/2026/10/12/002'
  }
  return '文件名 / 对象键包含'
})

/** 搜不到时给出下一步提示，避免在"精准"模式下拿目录去搜却不知道为什么没结果 */
const emptyHint = computed(() => {
  if (!query.keyword) {
    return '暂无图片'
  }
  if (query.matchMode === 'exact') {
    return '精准模式要求文件名或对象键完全相等。如果你填的是一段目录，请换成「目录」模式'
  }
  if (query.matchMode === 'prefix') {
    return '没有对象键或文件名以「' + query.keyword + '」开头'
  }
  return '没有包含「' + query.keyword + '」的图片'
})

/** 目录树 -> el-tree 数据；根目录下的散图单独一个节点 */
const treeData = computed<TreeNode[]>(() => {
  const convert = (nodes: DirectoryNodeVO[]): TreeNode[] =>
    nodes.map((node) => ({
      path: node.path,
      label: node.name,
      totalCount: node.totalCount,
      children: convert(node.children),
    }))
  const result: TreeNode[] = []
  if (dirTree.value.rootFileCount > 0) {
    result.push({
      path: ROOT_DIRECTORY,
      label: '（根目录）',
      totalCount: dirTree.value.rootFileCount,
      children: [],
    })
  }
  return result.concat(convert(dirTree.value.nodes))
})

/** 默认展开前两级，避免超大树一次性全展开 */
const expandedKeys = computed(() => {
  const keys: string[] = []
  const walk = (nodes: TreeNode[], depth: number) => {
    nodes.forEach((node) => {
      if (depth < 2) {
        keys.push(node.path)
        walk(node.children, depth + 1)
      }
    })
  }
  walk(treeData.value, 0)
  return keys
})

function toggleSelect(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selectedIds.value = next
}

function selectAllOnPage() {
  if (selectedIds.value.size === images.value.length && images.value.length > 0) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(images.value.map((item) => item.id))
  }
}

function clearSelection() {
  selectedIds.value = new Set()
}

function applySize(value: number) {
  const safe = Math.min(Math.max(Math.floor(value), 1), MAX_PAGE_SIZE)
  query.size = safe
  localStorage.setItem(SIZE_STORAGE_KEY, String(safe))
  load(1)
}

function onSizeOptionChange(value: number) {
  if (value === CUSTOM_SIZE) {
    customSize.value = query.size
    return
  }
  applySize(value)
}

function onCustomSizeChange(value: number | undefined) {
  if (!value) {
    return
  }
  applySize(value)
}

async function loadTree() {
  treeLoading.value = true
  try {
    dirTree.value = (await fetchDirectories(query.storageConfigId)).data
    treeVersion.value += 1
  } catch {
    // 拦截器已提示
  } finally {
    treeLoading.value = false
  }
}

function onStorageChange() {
  selectDirectory(undefined)
  loadTree()
  load(1)
}

function onNodeClick(node: TreeNode) {
  selectDirectory(node.path)
}

function selectDirectory(path?: string) {
  query.directory = path
  load(1)
}

function onDataChanged() {
  loadTree()
  load(1)
}

async function load(page?: number) {
  if (typeof page === 'number') {
    query.page = page
  }
  loading.value = true
  try {
    const params: {
      page: number
      size: number
      keyword?: string
      storageConfigId?: number
      directory?: string
      matchMode?: string
    } = {
      page: query.page,
      size: query.size,
    }
    if (query.keyword) {
      params.keyword = query.keyword
      params.matchMode = query.matchMode
    }
    if (query.storageConfigId) {
      params.storageConfigId = query.storageConfigId
    }
    if (query.directory) {
      params.directory = query.directory
    }
    const result = await fetchImages(params)
    images.value = result.data.records
    total.value = result.data.total
    selectedIds.value = new Set()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function copy(text: string, label: string) {
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success(label + '已复制到剪贴板')
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function remove(image: ImageVO) {
  try {
    await deleteImage(image.id)
    ElMessage.success('已删除')
    await load()
    await loadTree()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await load(1)
  await loadTree()
  try {
    configs.value = (await fetchStorageConfigs()).data
  } catch {
    // 忽略
  }
})
</script>

<style scoped>
.gallery-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.gallery-side {
  flex: 0 0 250px;
  width: 250px;
  max-height: calc(100vh - 130px);
  overflow: auto;
  position: sticky;
  top: 0;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.side-title {
  font-size: 13px;
  font-weight: 600;
}

.side-all {
  padding: 4px 8px;
  margin-bottom: 4px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}

.side-all:hover {
  background: #f5f7fa;
}

.side-all--active {
  color: #409eff;
  background: #ecf5ff;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 6px;
  font-size: 13px;
}

.tree-node__count {
  color: #a8abb2;
  font-size: 12px;
}

.gallery-main {
  flex: 1;
  min-width: 0;
}

.crumb {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  flex-wrap: wrap;
}

.pager__size {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
