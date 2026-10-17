<template>
  <div class="page" v-loading="loading">
    <PageHeader title="回收站" subtitle="删除的图片会先放在这里，可以恢复或彻底删除" />

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <el-input
            v-model="keyword"
            placeholder="文件名 / 对象键"
            clearable
            style="width: 220px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <div class="toolbar-spacer"></div>
          <el-button :disabled="selection.length === 0" :loading="working" @click="doRestore(selection)">
            <el-icon><RefreshLeft /></el-icon>&nbsp;恢复选中（{{ selection.length }}）
          </el-button>
          <el-button
            type="danger"
            plain
            :disabled="selection.length === 0"
            :loading="working"
            @click="confirmPurge(selection)"
          >
            彻底删除选中
          </el-button>
          <el-button type="danger" :disabled="total === 0" :loading="working" @click="confirmClear">
            清空回收站
          </el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="trash-tip"
        title="图库里的删除只是移入回收站：数据库打标记，仓库里的文件也会被搬进回收站目录（默认 _trash/，走仓库内引用式搬移，不产生流量）。
恢复会把文件搬回原位置；在这里再删一次才会真正删掉云端文件，且不可恢复。"
      />

      <!-- 手机端用卡片列表，避免表格只能横向滚动、操作列占满屏幕（U4） -->
      <TableCardList
        v-if="isMobile"
        :rows="rows"
        :fields="cardFields"
        :loading="loading"
        empty-text="回收站是空的"
        empty-description="在图库里删除的图片会先放在这里，可以恢复或彻底删除"
        empty-icon="DeleteFilled"
      >
        <template #field-originalName="{ row }">
          <span>{{ row.originalName }}</span>
        </template>
        <template #field-size="{ row }">
          {{ formatSize(row.size) }}
        </template>
        <template #field-trashKey="{ row }">
          <div>
            <el-tag v-if="row.trashKey" size="small" type="warning" effect="plain">已移入回收站目录</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">原地保留</el-tag>
            <div class="trash-key mono" :title="row.trashKey || row.objectKey">
              {{ row.trashKey || row.objectKey }}
            </div>
          </div>
        </template>
        <template #field-deletedAt="{ row }">
          {{ formatDate(row.deletedAt) }}
        </template>
        <template #actions="{ row }">
          <el-button link type="primary" size="small" :disabled="working" @click="doRestore([row])">恢复</el-button>
          <el-button link type="danger" size="small" :disabled="working" @click="confirmPurge([row])">彻底删除</el-button>
        </template>
      </TableCardList>

      <el-table v-else :data="rows" style="width: 100%" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column label="预览" width="86">
          <template #default="{ row }">
            <el-image
              class="trash-thumb"
              :src="row.thumbUrl || row.previewUrl || row.url"
              :alt="row.originalName || '图片'"
              fit="cover"
              :preview-src-list="[row.previewUrl || row.url]"
              preview-teleported
              hide-on-click-modal
            >
              <template #error>
                <div class="trash-thumb trash-thumb--empty"><el-icon><Picture /></el-icon></div>
              </template>
            </el-image>
          </template>
        </el-table-column>
        <el-table-column prop="originalName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="云端位置" min-width="240">
          <template #default="{ row }">
            <el-tag v-if="row.trashKey" size="small" type="warning" effect="plain">已移入回收站目录</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">原地保留</el-tag>
            <div class="trash-key mono" :title="row.trashKey || row.objectKey">
              {{ row.trashKey || row.objectKey }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="移入时间" width="170">
          <template #default="{ row }">{{ formatDate(row.deletedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" :fixed="isMobile ? undefined : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="working" @click="doRestore([row])">恢复</el-button>
            <el-button link type="danger" size="small" :disabled="working" @click="confirmPurge([row])">
              彻底删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            icon="DeleteFilled"
            title="回收站是空的"
            description="在图库里删除的图片会先放在这里，可以恢复或彻底删除" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          :pager-count="7"
          layout="total, prev, pager, next, jumper"
          background
          @current-change="load()"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useIsMobile } from '@/utils/responsive'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import TableCardList from '@/components/TableCardList.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearTrash, fetchTrash, purgeImages, restoreImages } from '@/api/images'
import { formatDate, formatSize } from '@/utils/format'

/** el-table 插槽行是宽松类型，只声明用得到的字段 */
interface TrashRow {
  id?: number
  originalName?: string
}

const isMobile = useIsMobile()

/** 手机端卡片模式下的字段（与表格列同源） */
const cardFields = [
  { label: '文件名', prop: 'originalName' },
  { label: '大小', prop: 'size' },
  { label: '云端位置', prop: 'trashKey' },
  { label: '移入时间', prop: 'deletedAt' },
]
const loading = ref(false)
const working = ref(false)
const rows = ref<TrashRow[]>([])
const total = ref(0)
const keyword = ref('')
const query = reactive({ page: 1, size: 20 })
const selection = ref<TrashRow[]>([])

async function load(page?: number, retried = false) {
  if (typeof page === 'number') {
    query.page = page
  }
  loading.value = true
  try {
    const params: { page: number; size: number; keyword?: string } = { page: query.page, size: query.size }
    if (keyword.value) {
      params.keyword = keyword.value
    }
    const result = await fetchTrash(params)
    rows.value = result.data.records
    total.value = result.data.total
    selection.value = []
    // 彻底删掉当页最后一条后当前页可能越界，自动退回最后一页
    const pages = Math.max(1, Math.ceil(total.value / query.size))
    if (!retried && query.page > pages) {
      query.page = pages
      await load(undefined, true)
    }
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onSelectionChange(value: TrashRow[]) {
  selection.value = value
}

function idsOf(targets: TrashRow[]) {
  return targets.map((item) => item.id).filter((id): id is number => typeof id === 'number')
}

async function doRestore(targets: TrashRow[]) {
  const ids = idsOf(targets)
  if (ids.length === 0) {
    return
  }
  working.value = true
  try {
    const result = await restoreImages(ids)
    ElMessage.success('已恢复 ' + result.data + ' 张图片，云端文件也搬回原位了')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    working.value = false
  }
}

async function confirmPurge(targets: TrashRow[]) {
  const ids = idsOf(targets)
  if (ids.length === 0) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '将同时删除云端文件与本地记录，删除后无法恢复，确认彻底删除这 ' + ids.length + ' 张图片？',
      '彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  working.value = true
  try {
    const result = await purgeImages(ids)
    ElMessage.success('已彻底删除 ' + result.data + ' 张图片')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    working.value = false
  }
}

async function confirmClear() {
  try {
    await ElMessageBox.confirm(
      '回收站里的 ' + total.value + ' 张图片会被全部彻底删除（含云端文件），且无法恢复，确认继续？',
      '清空回收站',
      { type: 'warning', confirmButtonText: '清空', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  working.value = true
  try {
    const result = await clearTrash()
    ElMessage.success('已清空回收站，彻底删除 ' + result.data + ' 张图片')
    await load(1)
  } catch {
    // 拦截器已提示
  } finally {
    working.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.trash-tip {
  margin-bottom: 12px;
}

.trash-tip :deep(.el-alert__title) {
  line-height: 1.6;
}

.trash-thumb {
  width: 56px;
  height: 42px;
  display: block;
  border-radius: 6px;
  overflow: hidden;
  background: #f0f2f5;
}

.trash-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.trash-key {
  margin-top: 4px;
  color: #909399;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
