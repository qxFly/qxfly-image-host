<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="page-title" style="margin: 0">存储设置</h2>
      <div class="toolbar-spacer"></div>
      <el-button :loading="checking" @click="doCheck"><el-icon><Operation /></el-icon>&nbsp;立即体检</el-button>
      <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>&nbsp;新增存储后端
      </el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        SecretKey / Token 等敏感字段以 AES-GCM 加密存入数据库，接口回显时统一替换为 ******；
        编辑时保留占位符即表示不修改原值。
      </template>
    </el-alert>

    <!-- 手机端用卡片列表（U4） -->
    <TableCardList
      v-if="isMobile"
      :rows="configs"
      :fields="cardFields"
      :loading="loading"
      empty-text="还没有配置任何存储后端"
      empty-description="先添加一个存储后端，再回到图库上传图片"
      empty-icon="Coin"
    >
      <template #field-name="{ row }">
        <span>{{ row.name }}</span>
        <el-tag v-if="row.isDefault" size="small" type="success" effect="dark" style="margin-left: 6px">默认</el-tag>
      </template>
      <template #field-enabled="{ row }">
        <el-tag size="small" effect="plain" :type="row.enabled ? 'success' : 'info'">
          {{ row.enabled ? '启用' : '停用' }}
        </el-tag>
      </template>
      <template #field-updatedAt="{ row }">{{ formatDate(row.updatedAt) }}</template>
      <template #field-lastCheckOk="{ row }">
        <el-tag v-if="row.lastCheckOk === true" size="small" type="success" effect="plain">正常</el-tag>
        <el-tag v-else-if="row.lastCheckOk === false" size="small" type="danger" effect="plain" :title="row.lastCheckMessage">异常</el-tag>
        <span v-else class="text-muted">未检测</span>
      </template>
      <template #actions="{ row }">
        <el-button size="small" link type="primary" :loading="testingId === row.id" @click="doTest(row)">测试</el-button>
        <el-button size="small" link type="primary" @click="openCdn(row)">CDN</el-button>
        <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
        <el-button size="small" link type="primary" :disabled="row.isDefault" @click="markDefault(row)">设为默认</el-button>
        <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
      </template>
    </TableCardList>

    <el-table v-else v-loading="loading" :data="configs" style="width: 100%">
      <el-table-column label="名称" min-width="150">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag v-if="row.isDefault" size="small" type="success" effect="dark" style="margin-left: 6px">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="typeLabel" label="类型" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="健康" width="130">
        <template #default="{ row }">
          <el-tooltip v-if="row.lastCheckMessage" :content="String(row.lastCheckAt) + '　' + row.lastCheckMessage" placement="top">
            <el-tag size="small" effect="plain" :type="row.lastCheckOk ? 'success' : 'danger'">
              {{ row.lastCheckOk ? '正常' : '异常' }}
            </el-tag>
          </el-tooltip>
          <span v-else class="text-muted">未检测</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="350" :fixed="isMobile ? undefined : 'right'">
        <template #default="{ row }">
          <el-button size="small" link type="primary" :loading="testingId === row.id" @click="doTest(row)">
            测试
          </el-button>
          <el-button size="small" link type="primary" @click="openCdn(row)">CDN</el-button>
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="primary" :disabled="row.isDefault" @click="markDefault(row)">
            设为默认
          </el-button>
          <el-popconfirm title="确认删除该存储配置？" width="220" @confirm="remove(row)">
            <template #reference>
              <el-button size="small" link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState
          icon="Coin"
          title="还没有配置任何存储后端"
          description="先添加一个存储后端，再回到图库上传图片"
          action-text="新增存储后端"
          @action="openCreate" />
      </template>
    </el-table>

    <StorageFormDialog v-model="dialogVisible" :types="types" :editing="editing" @saved="load" />
    <StorageCdnDialog v-model="cdnVisible" :config="cdnConfig" />
  </div>
</template>

<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
import TableCardList from '@/components/TableCardList.vue'
import { useIsMobile } from '@/utils/responsive'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StorageCdnDialog from '@/components/StorageCdnDialog.vue'
import StorageFormDialog from '@/components/StorageFormDialog.vue'
import {
  deleteStorageConfig,
  fetchStorageConfigs,
  fetchStorageTypes,
  setDefaultStorageConfig,
  testStorageConfig,
} from '@/api/storage'
import { formatDate } from '@/utils/format'
import { checkStorageConfigs } from '@/api/storage'
import type { StorageConfigVO, StorageTypeVO } from '@/types'

const isMobile = useIsMobile()

/** 手机端卡片模式字段 */
const cardFields = [
  { label: '名称', prop: 'name' },
  { label: '类型', prop: 'typeLabel' },
  { label: '状态', prop: 'enabled' },
  { label: '备注', prop: 'remark' },
  { label: '更新时间', prop: 'updatedAt' },
  { label: '健康', prop: 'lastCheckOk' },
]
const loading = ref(false)
const configs = ref<StorageConfigVO[]>([])
const types = ref<StorageTypeVO[]>([])
const dialogVisible = ref(false)
const editing = ref<StorageConfigVO | null>(null)
const testingId = ref<number | null>(null)
/** 定时体检的「立即体检」进行中 */
const checking = ref(false)
const cdnVisible = ref(false)
const cdnConfig = ref<StorageConfigVO | null>(null)

/** 手动体检：把启用的配置全部真测一遍，结果写进健康列（失败也会进审计日志） */
async function doCheck() {
  checking.value = true
  try {
    await checkStorageConfigs()
    await load()
    ElMessage.success('体检完成，结果见「健康」列')
  } catch {
    // 拦截器已提示
  } finally {
    checking.value = false
  }
}

async function load() {
  loading.value = true
  try {
    configs.value = (await fetchStorageConfigs()).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openCdn(row: any) {
  cdnConfig.value = row
  cdnVisible.value = true
}

function openEdit(row: any) {
  editing.value = row
  dialogVisible.value = true
}

async function doTest(row: any) {
  testingId.value = row.id
  try {
    const result = await testStorageConfig(row.id)
    ElMessage.success(result.data.message || '连接成功')
  } catch {
    // 拦截器已提示失败原因
  } finally {
    testingId.value = null
  }
}

async function markDefault(row: any) {
  try {
    await setDefaultStorageConfig(row.id)
    ElMessage.success('已设为默认存储后端')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(row: any) {
  try {
    await deleteStorageConfig(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await load()
  try {
    types.value = (await fetchStorageTypes()).data
  } catch {
    // 拦截器已提示
  }
})
</script>
