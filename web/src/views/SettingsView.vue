<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">系统设置</h2>

    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>上传限制</span>
          <div class="toolbar-spacer"></div>
          <span class="text-muted">改完立即生效，不用重启</span>
        </div>
      </template>

      <el-form label-width="150px" style="max-width: 640px">
        <el-form-item label="单张文件大小">
          <el-switch v-model="unlimited" active-text="无上限" />
          <span class="text-muted" style="margin-left: 12px">打开后不再限制单张大小</span>
        </el-form-item>
        <el-form-item v-if="!unlimited" label="上限">
          <el-input-number v-model="limitMb" :min="1" :max="maxMb" :step="10" />
          <span class="text-muted" style="margin-left: 8px">MB</span>
        </el-form-item>
        <el-form-item label="当前生效">
          <el-tag :type="form && form.unlimitedUploadSize ? 'warning' : 'info'" effect="plain">
            {{ form ? form.maxUploadSizeText : '-' }}
          </el-tag>
          <span class="text-muted" style="margin-left: 8px">
            出厂默认 {{ formatSize(form ? form.defaultMaxUploadSize : 0) }}
          </span>
        </el-form-item>
        <el-divider content-position="left">登录安全</el-divider>
        <el-form-item label="登录失败锁定">
          <el-input-number v-model="loginMaxAttempts" :min="1" :max="100" />
          <span class="text-muted" style="margin-left: 8px">次后锁定</span>
          <el-input-number v-model="loginLockMinutes" :min="1" :max="1440" style="margin-left: 16px" />
          <span class="text-muted" style="margin-left: 8px">分钟</span>
        </el-form-item>
        <el-form-item label="说明">
          <span class="text-muted">
            同一账号连续输错密码达到次数后锁定，期间即使密码正确也拒绝登录；
            计数只存在内存里，重启即清空。失败与锁定都会写进审计日志。
          </span>
        </el-form-item>
        <el-divider content-position="left">防盗链</el-divider>
        <el-form-item label="开启防盗链">
          <el-switch v-model="hotlinkEnabled" />
          <span class="text-muted" style="margin-left: 12px">
            只对本机存储的 /files 直链与缩略图生效（GitHub / OSS 等要在各自控制台配）
          </span>
        </el-form-item>
        <el-form-item v-if="hotlinkEnabled" label="允许的域名">
          <el-input
            v-model="hotlinkDomains"
            placeholder="例如 a.com,b.com（子域自动放行，本站永远放行）"
          />
        </el-form-item>
        <el-form-item v-if="hotlinkEnabled" label="无 Referer 时">
          <el-switch v-model="hotlinkAllowEmpty" active-text="放行" inactive-text="拦截" />
          <span class="text-muted" style="margin-left: 12px">
            直接粘贴图片地址、浏览器隐私设置都会没有 Referer，建议放行
          </span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="load">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" show-icon>
        <template #title>关于「无上限」</template>
        打开后服务端不再拦截大文件，但仍受磁盘剩余空间、请求超时与网络带宽限制；
        浏览器端会把整个文件读完再上传（大文件建议在网络稳定时传）。
        如需在服务端加一道硬兜底，可用环境变量 <code>UPLOAD_SERVLET_MAX</code>（例如 2GB）。
      </el-alert>
    </el-card>

    <el-card shadow="never">
      <template #header><span>系统信息</span></template>
      <el-descriptions v-if="systemInfo" :column="2" border size="small">
        <el-descriptions-item label="服务名称">{{ systemInfo.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ systemInfo.version }}</el-descriptions-item>
        <el-descriptions-item label="单张大小上限">{{ systemInfo.maxUploadSizeText }}</el-descriptions-item>
        <el-descriptions-item label="允许的格式">{{ systemInfo.allowedExtensions.join(' / ') }}</el-descriptions-item>
        <el-descriptions-item label="存储后端">
          <el-tag v-for="item in systemInfo.storageTypes" :key="item" size="small" effect="plain" style="margin-right: 4px">
            {{ item }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="相同图片秒传">{{ systemInfo.dedupeEnabled ? '已开启' : '已关闭' }}</el-descriptions-item>
        <el-descriptions-item label="目录分片阈值">
          {{ systemInfo.pathShardSize ? systemInfo.pathShardSize + ' 个/目录' : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="扫描导入上限">
          {{ systemInfo.importMaxFiles ? systemInfo.importMaxFiles + ' 个文件' : '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchSettings, updateSettings } from '@/api/settings'
import { fetchSystemInfo } from '@/api/system'
import { formatSize } from '@/utils/format'
import type { SettingsVO, SystemInfo } from '@/types'

const loading = ref(false)
const saving = ref(false)
const form = ref<SettingsVO | null>(null)
const systemInfo = ref<SystemInfo | null>(null)
const unlimited = ref(false)
const limitMb = ref(30)
const loginMaxAttempts = ref(5)
const loginLockMinutes = ref(15)
const hotlinkEnabled = ref(false)
const hotlinkDomains = ref('')
const hotlinkAllowEmpty = ref(true)

/** 后端允许的设置上限（字节）换算成 MB，作为输入框上限 */
const maxMb = computed(() => {
  const limit = form.value ? form.value.maxUploadSizeLimit : 0
  return limit > 0 ? Math.floor(limit / 1024 / 1024) : 1048576
})

async function load() {
  loading.value = true
  try {
    form.value = (await fetchSettings()).data
    unlimited.value = form.value.unlimitedUploadSize
    limitMb.value = form.value.unlimitedUploadSize
      ? 30
      : Math.max(1, Math.round(form.value.maxUploadSize / 1024 / 1024))
    loginMaxAttempts.value = form.value.loginMaxAttempts
    loginLockMinutes.value = form.value.loginLockMinutes
    hotlinkEnabled.value = form.value.hotlinkEnabled
    hotlinkDomains.value = form.value.hotlinkDomains || ''
    hotlinkAllowEmpty.value = form.value.hotlinkAllowEmpty
  } catch {
    // 拦截器已经提示
  } finally {
    loading.value = false
  }
  try {
    systemInfo.value = (await fetchSystemInfo()).data
  } catch {
    // 忽略
  }
}

async function save() {
  saving.value = true
  try {
    const data: Record<string, unknown> = {
      loginMaxAttempts: loginMaxAttempts.value,
      loginLockMinutes: loginLockMinutes.value,
      hotlinkEnabled: hotlinkEnabled.value,
      hotlinkDomains: hotlinkDomains.value,
      hotlinkAllowEmpty: hotlinkAllowEmpty.value,
    }
    if (unlimited.value) {
      data.unlimitedUploadSize = true
    } else {
      data.maxUploadSize = Math.round(limitMb.value) * 1024 * 1024
    }
    form.value = (await updateSettings(data)).data
    unlimited.value = form.value.unlimitedUploadSize
    limitMb.value = form.value.unlimitedUploadSize
      ? 30
      : Math.max(1, Math.round(form.value.maxUploadSize / 1024 / 1024))
    loginMaxAttempts.value = form.value.loginMaxAttempts
    loginLockMinutes.value = form.value.loginLockMinutes
    hotlinkEnabled.value = form.value.hotlinkEnabled
    hotlinkDomains.value = form.value.hotlinkDomains || ''
    hotlinkAllowEmpty.value = form.value.hotlinkAllowEmpty
    ElMessage.success('已保存，单张上限：' + form.value.maxUploadSizeText)
    systemInfo.value = (await fetchSystemInfo()).data
  } catch {
    // 拦截器已经提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
