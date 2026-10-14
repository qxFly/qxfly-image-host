<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">审计日志</h2>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <el-input
            v-model="keyword"
            placeholder="用户名 / 详情 / IP"
            clearable
            style="width: 220px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="action" placeholder="全部动作" clearable style="width: 170px" @change="load(1)">
            <el-option v-for="item in ACTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="success" placeholder="全部结果" clearable style="width: 130px" @change="load(1)">
            <el-option label="成功" :value="true" />
            <el-option label="失败" :value="false" />
          </el-select>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
          <div class="toolbar-spacer"></div>
          <span class="text-muted">共 {{ total }} 条（只记会改数据的操作与登录，翻页不记录）</span>
        </div>
      </template>

      <el-table :data="rows" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ row.createdAt }}</template>
        </el-table-column>
        <el-table-column label="操作者" width="140">
          <template #default="{ row }">
            <span v-if="row.username">{{ row.username }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.success === 1 ? 'primary' : 'danger'">
              {{ labelOf(row.action) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.targetType">{{ row.targetType }}#{{ row.targetId }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip />
        <el-table-column label="来源 IP" width="140">
          <template #default="{ row }">
            <span class="mono">{{ row.ip || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="toolbar" style="margin-top: 12px; justify-content: center">
        <el-pagination
          background
          layout="prev, pager, next, sizes, jumper, total"
          :total="total"
          :current-page="page"
          :page-size="size"
          :page-sizes="[20, 50, 100, 200]"
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchAuditLogs } from '@/api/audit'
import type { AuditLogVO } from '@/types'

const ACTIONS = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGIN_FAILED', label: '登录失败' },
  { value: 'LOGIN_LOCKED', label: '登录锁定' },
  { value: 'UPLOAD', label: '上传' },
  { value: 'TRASH_MOVE', label: '移入回收站' },
  { value: 'TRASH_RESTORE', label: '回收站恢复' },
  { value: 'IMAGE_PURGE', label: '彻底删除' },
  { value: 'TRASH_CLEAR', label: '清空回收站' },
  { value: 'USER_CREATE', label: '新建账号' },
  { value: 'USER_UPDATE', label: '改账号资料' },
  { value: 'USER_STATUS', label: '启停账号' },
  { value: 'USER_PASSWORD', label: '重置密码' },
  { value: 'USER_DELETE', label: '删除账号' },
  { value: 'STORAGE_CREATE', label: '新建存储' },
  { value: 'STORAGE_UPDATE', label: '改存储配置' },
  { value: 'STORAGE_DELETE', label: '删存储配置' },
  { value: 'STORAGE_DEFAULT', label: '设默认存储' },
  { value: 'SETTINGS_UPDATE', label: '改系统设置' },
  { value: 'SHARE_CREATE', label: '创建分享' },
  { value: 'SHARE_DELETE', label: '取消分享' },
]

const loading = ref(false)
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const keyword = ref('')
const action = ref<string | undefined>(undefined)
const success = ref<boolean | undefined>(undefined)

function labelOf(value: string) {
  const hit = ACTIONS.find((item) => item.value === value)
  return hit ? hit.label : value
}

async function load(target?: number) {
  if (typeof target === 'number') {
    page.value = target
  }
  loading.value = true
  try {
    const result = await fetchAuditLogs({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      action: action.value || undefined,
      success: success.value,
    })
    rows.value = result.data.records
    total.value = result.data.total
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onSizeChange(value: number) {
  size.value = value
  load(1)
}

function reset() {
  keyword.value = ''
  action.value = undefined
  success.value = undefined
  load(1)
}

onMounted(() => load(1))
</script>
