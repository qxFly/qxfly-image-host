<template>
  <div class="page">
    <h2 class="page-title">个人中心</h2>

    <el-row :gutter="isMobile ? 0 : 16">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" style="margin-bottom: 16px">
          <template #header>基本资料</template>
          <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-width="90px">
            <el-form-item label="用户名">
              <el-input :model-value="auth.user ? auth.user.username : ''" disabled />
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="profileForm.nickname" placeholder="请输入昵称" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="profileForm.email" placeholder="选填" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never">
          <template #header>修改密码</template>
          <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="90px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="passwordForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="passwordForm.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingPassword" @click="savePassword">修改密码</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>系统信息</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="服务名称">{{ systemInfo ? systemInfo.name : '-' }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ systemInfo ? systemInfo.version : '-' }}</el-descriptions-item>
            <el-descriptions-item label="已接入后端">
              <el-tag
                v-for="item in systemInfo ? systemInfo.storageTypes : []"
                :key="item"
                size="small"
                effect="plain"
                style="margin: 2px 4px 2px 0"
              >
                {{ item }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="单张上限">
              {{ systemInfo ? systemInfo.maxUploadSizeText : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="允许格式">
              {{ systemInfo ? systemInfo.allowedExtensions.join(' / ') : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="内容秒传">
              {{ systemInfo && systemInfo.dedupeEnabled ? '已开启' : '已关闭' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" style="margin-top: 16px">
          <template #header>
            <div class="toolbar" style="margin: 0">
              <span>本地图片缓存</span>
              <div class="toolbar-spacer"></div>
              <el-button size="small" link type="primary" @click="refreshCache">刷新</el-button>
            </div>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="已缓存">{{ cacheInfo.entries }} 张</el-descriptions-item>
            <el-descriptions-item label="占用空间">{{ formatSize(cacheInfo.bytes) }}</el-descriptions-item>
            <el-descriptions-item label="可用状态">
              {{ cacheInfo.supported ? '可用' : '当前环境不支持 IndexedDB' }}
            </el-descriptions-item>
          </el-descriptions>
          <div class="text-muted" style="margin-top: 8px; line-height: 1.7">
            图库缩略图会存到浏览器本地。再次进入图库先读缓存，不再重新下载；
            超过 30 分钟才带 ETag 做一次条件请求，服务端返回 304 就继续复用缓存。
            最多缓存 {{ 3000 }} 张，超出后自动淘汰最久未使用的。
          </div>
          <el-button style="margin-top: 10px" :disabled="cacheInfo.entries === 0" @click="clearCache">
            清空图片缓存
          </el-button>
        </el-card>

        <!-- 临时工具走的是管理类接口，普通用户看不到 -->
        <el-card v-if="auth.isSuperAdmin" shadow="never" style="margin-top: 16px">
          <template #header>
            <div class="toolbar" style="margin: 0">
              <span>临时工具</span>
              <el-tag size="small" type="warning" effect="plain" style="margin-left: 8px">仅 GitHub</el-tag>
            </div>
          </template>
          <div class="text-muted" style="line-height: 1.7">
            把仓库里某个目录下的图片文件按系统存储规则搬到另一个目录（例如
            <span class="mono">api</span> → <span class="mono">api/images</span>）。
            同仓库移动直接复用文件原有的 blob，只产生一个提交，不会重新下载再上传，几千个文件也很快。
          </div>
          <el-button style="margin-top: 10px" @click="repoMoveVisible = true">仓库目录整理</el-button>
        </el-card>
      </el-col>
    </el-row>

    <RepoMoveDialog v-model="repoMoveVisible" @finished="onRepoMoved" />
  </div>
</template>

<script setup lang="ts">
import { useIsMobile } from '@/utils/responsive'
import RepoMoveDialog from '@/components/RepoMoveDialog.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePassword, updateProfile } from '@/api/auth'
import { fetchSystemInfo } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { cacheStats, clearImageCache } from '@/utils/imageCache'
import { formatSize } from '@/utils/format'
import type { SystemInfo } from '@/types'

const auth = useAuthStore()
const isMobile = useIsMobile()
const systemInfo = ref<SystemInfo | null>(null)
const cacheInfo = ref({ entries: 0, bytes: 0, supported: true })
const repoMoveVisible = ref(false)
const profileFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const savingProfile = ref(false)
const savingPassword = ref(false)

const profileForm = reactive({ nickname: '', email: '' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const profileRules: FormRules = {
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

onMounted(async () => {
  const user = auth.user || (await auth.loadUser().catch(() => null))
  if (user) {
    profileForm.nickname = user.nickname || ''
    profileForm.email = user.email || ''
  }
  try {
    systemInfo.value = (await fetchSystemInfo()).data
  } catch {
    // 忽略
  }
  await refreshCache()
})

function onRepoMoved() {
  // 目录整理后图库的目录结构变了，这里只提示一下，用户回到图库刷新即可
  ElMessage.info('仓库目录已整理，回到图库刷新即可看到新的目录结构')
}

async function refreshCache() {
  cacheInfo.value = await cacheStats()
}

async function clearCache() {
  await clearImageCache()
  ElMessage.success('图片缓存已清空')
  await refreshCache()
}

async function saveProfile() {
  const valid = await profileFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  savingProfile.value = true
  try {
    await updateProfile({ nickname: profileForm.nickname, email: profileForm.email })
    await auth.loadUser()
    ElMessage.success('资料已更新')
  } catch {
    // 拦截器已提示
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  savingPassword.value = true
  try {
    await changePassword({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword })
    ElMessage.success('密码修改成功，请重新登录')
    auth.logout()
    window.location.hash = '#/login'
  } catch {
    // 拦截器已提示
  } finally {
    savingPassword.value = false
  }
}
</script>
