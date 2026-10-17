<template>
  <div class="page" v-loading="loading">
    <PageHeader title="用户管理" subtitle="账号、角色与启用状态；登录失败锁定次数与账号无关" />

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <el-input
            v-model="keyword"
            placeholder="用户名 / 昵称"
            clearable
            style="width: 220px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <div class="toolbar-spacer"></div>
          <el-tooltip
            placement="bottom"
            content="超级管理员只能通过数据库赋予：UPDATE sys_user SET role = 'SUPER_ADMIN' WHERE username = 'xxx'; 改完即时生效"
          >
            <el-tag type="warning" effect="plain" size="small" class="role-hint">
              <el-icon><InfoFilled /></el-icon>&nbsp;超级管理员由数据库赋予
            </el-tag>
          </el-tooltip>
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>&nbsp;新建用户
          </el-button>
        </div>
      </template>

      <!-- 手机端用卡片列表（U4） -->
      <TableCardList
        v-if="isMobile"
        :rows="users"
        :fields="cardFields"
        :loading="loading"
        empty-text="没有匹配的账号"
        empty-description="换个关键字试试，或新建一个账号"
        empty-icon="UserFilled"
      >
        <template #field-username="{ row }">{{ row.username }}</template>
        <template #field-nickname="{ row }">{{ row.nickname || '—' }}</template>
        <template #field-email="{ row }">{{ row.email || '—' }}</template>
        <template #field-role="{ row }">
          <el-tag :type="isSuperAdminRow(row) ? 'warning' : 'info'" effect="plain" size="small">
            {{ row.roleLabel || (isSuperAdminRow(row) ? '超级管理员' : '普通用户') }}
          </el-tag>
        </template>
        <template #field-status="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :disabled="isProtected(row)"
            @change="(value: string | number | boolean) => toggleStatus(row, value === true)" />
          <span class="text-muted" style="margin-left: 8px">{{ row.status === 1 ? '启用' : '禁用' }}</span>
        </template>
        <template #field-createdAt="{ row }">{{ formatDate(row.createdAt) }}</template>
        <template #actions="{ row }">
          <el-button link type="primary" size="small" :disabled="isSuperAdminRow(row)" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" :disabled="isSuperAdminRow(row)" @click="openReset(row)">重置密码</el-button>
          <el-popconfirm title="确认删除该账号？" width="210" @confirm="remove(row)">
            <template #reference>
              <el-button link type="danger" size="small" :disabled="isProtected(row)">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </TableCardList>

      <el-table v-else :data="users" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <el-tag :type="isSuperAdminRow(row) ? 'warning' : 'info'" effect="plain" size="small">
              {{ row.roleLabel || (isSuperAdminRow(row) ? '超级管理员' : '普通用户') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              :disabled="isProtected(row)"
              @change="(value: string | number | boolean) => toggleStatus(row, value === true)"
            />
            <span class="text-muted" style="margin-left: 8px">{{ row.status === 1 ? '启用' : '禁用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" :fixed="isMobile ? undefined : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="isSuperAdminRow(row)" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button link type="primary" size="small" :disabled="isSuperAdminRow(row)" @click="openReset(row)">
              重置密码
            </el-button>
            <el-popconfirm title="确认删除该账号？" width="210" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger" size="small" :disabled="isProtected(row)">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            icon="UserFilled"
            title="没有匹配的账号"
            description="换个关键字试试，或新建一个账号"
            action-text="新建用户"
            @action="openCreate" />
        </template>
      </el-table>

      <!-- 分页栏居中，和图片列表保持一致 -->
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

    <el-dialog v-model="createVisible" title="新建用户" width="460px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="新建的账号都是普通用户，只能浏览、查看与下载"
        style="margin-bottom: 14px"
      />
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="72px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="3-32 位字母、数字、下划线或短横线" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="createForm.nickname" placeholder="选填，默认用用户名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑账号资料" width="460px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="用户名是登录凭据、角色只能改数据库，所以这里只能改昵称和邮箱"
        style="margin-bottom: 14px"
      />
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="72px">
        <el-form-item label="用户名">
          <span class="mono">{{ editTarget?.username }}</span>
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="editForm.nickname" placeholder="留空则用用户名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="420px">
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="72px">
        <el-form-item label="账号">
          <span class="mono">{{ resetTarget?.username }}</span>
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="6-64 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import TableCardList from '@/components/TableCardList.vue'
import { useIsMobile } from '@/utils/responsive'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createUser,
  deleteUser,
  fetchUsers,
  resetUserPassword,
  updateUser,
  updateUserStatus,
} from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { formatDate } from '@/utils/format'
import type { UserVO } from '@/types'

const auth = useAuthStore()
const isMobile = useIsMobile()

/** 手机端卡片模式字段（与表格列同源） */
const cardFields = [
  { label: 'ID', prop: 'id' },
  { label: '用户名', prop: 'username' },
  { label: '昵称', prop: 'nickname' },
  { label: '邮箱', prop: 'email' },
  { label: '角色', prop: 'role' },
  { label: '状态', prop: 'status' },
  { label: '创建时间', prop: 'createdAt' },
]
const loading = ref(false)
const submitting = ref(false)
const users = ref<UserVO[]>([])
const total = ref(0)
const keyword = ref('')
const query = reactive({ page: 1, size: 20 })

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({ username: '', password: '', nickname: '', email: '' })
const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_-]{3,32}$/, message: '3-32 位字母、数字、下划线或短横线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const editVisible = ref(false)
const editTarget = ref<UserRow | null>(null)
const editFormRef = ref<FormInstance>()
const editForm = reactive({ nickname: '', email: '' })
const editRules: FormRules = {
  nickname: [{ max: 64, message: '昵称最长 64 个字符', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const resetVisible = ref(false)
const resetTarget = ref<UserRow | null>(null)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({ newPassword: '' })
const resetRules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' },
  ],
}

/**
 * el-table 插槽里的行是宽松类型，这里只声明用得到的字段，
 * 避免每个插槽都要断言成 UserVO。
 */
interface UserRow {
  id?: number
  username?: string
  nickname?: string
  email?: string
  role?: string
  roleLabel?: string
  status?: number
}

/** 超级管理员账号是「数据库专属」：界面上不能改状态、不能重置密码、不能删除 */
function isSuperAdminRow(row: UserRow) {
  return row.role === 'SUPER_ADMIN' || row.role === 'ADMIN'
}

function isProtected(row: UserRow) {
  return isSuperAdminRow(row) || (!!row.id && row.id === auth.user?.id)
}

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
    const result = await fetchUsers(params)
    users.value = result.data.records
    total.value = result.data.total
    // 删掉当页最后一条后当前页可能越界，自动退回最后一页
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

async function toggleStatus(row: UserRow, enabled: boolean) {
  if (!row.id) {
    return
  }
  try {
    await updateUserStatus(row.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '已启用 ' + (row.username || '') : '已禁用 ' + (row.username || ''))
  } catch {
    // 拦截器已提示
  } finally {
    // 开关绑定的是列表数据，重新拉一次即可回滚显示
    await load()
  }
}

function openCreate() {
  createForm.username = ''
  createForm.password = ''
  createForm.nickname = ''
  createForm.email = ''
  createVisible.value = true
}

async function doCreate() {
  if (!createFormRef.value) {
    return
  }
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await createUser({ ...createForm })
    ElMessage.success('账号已创建（普通用户）')
    createVisible.value = false
    await load(1)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function openEdit(row: UserRow) {
  editTarget.value = row
  editForm.nickname = row.nickname || ''
  editForm.email = row.email || ''
  editVisible.value = true
}

async function doEdit() {
  const targetId = editTarget.value?.id
  if (!editFormRef.value || !targetId) {
    return
  }
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await updateUser(targetId, { nickname: editForm.nickname, email: editForm.email })
    ElMessage.success('资料已更新')
    editVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function openReset(row: UserRow) {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetVisible.value = true
}

async function doReset() {
  const targetId = resetTarget.value?.id
  if (!resetFormRef.value || !targetId) {
    return
  }
  const valid = await resetFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await resetUserPassword(targetId, resetForm.newPassword)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

async function remove(row: UserRow) {
  if (!row.id) {
    return
  }
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.role-hint {
  cursor: help;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
