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
        <el-divider content-position="left">缩略图缓存</el-divider>
        <el-form-item v-if="thumbCache" label="占用">
          <span class="mono">
            {{ formatSize(thumbCache.bytes) }} / {{ formatSize(thumbCache.maxBytes) }}
            · {{ thumbCache.files }} 个文件 · 保留 {{ thumbCache.days }} 天
          </span>
          <div class="toolbar-spacer"></div>
          <el-button type="danger" plain :loading="clearingCache" @click="clearCache">清空缓存</el-button>
        </el-form-item>
        <el-form-item v-if="thumbCache" label="目录">
          <span class="mono text-muted" style="word-break: break-all">{{ thumbCache.dir }}</span>
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

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>数据备份</span>
          <div class="toolbar-spacer"></div>
          <span class="text-muted">导出的是元数据清单，不含图片文件本身</span>
        </div>
      </template>
      <el-form label-width="150px" style="max-width: 640px">
        <el-form-item label="导出清单">
          <el-button type="primary" plain @click="doExport('csv')">导出 CSV</el-button>
          <el-button plain @click="doExport('json')">导出 JSON</el-button>
          <div class="text-muted" style="margin-top: 6px">
            CSV 带 BOM，可直接用 Excel 打开；包含对象键、访问地址、大小、sha256、宽高、存储类型与回收站状态。
            图片本身仍在云端 / 磁盘上，清单只记录“它们在哪”。
          </div>
        </el-form-item>
        <el-form-item label="从清单回灌">
          <el-upload :show-file-list="false" :auto-upload="false" :on-change="onManifestFile" accept=".csv,.json">
            <el-button :loading="restoring">选择 CSV / JSON 文件</el-button>
          </el-upload>
          <div class="text-muted" style="margin-top: 6px">
            库里没有的记录会被补回来（按对象键去重、可重复执行），不会重新下载或上传任何文件。
            典型场景：换数据库后把清单导回来，或者误删了记录。
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportManifest, importManifest } from '@/api/images'
import { clearThumbCache, fetchSettings, fetchThumbCache, updateSettings } from '@/api/settings'
import { saveBlob } from '@/utils/download'
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
const thumbCache = ref<{ dir: string; files: number; bytes: number; maxBytes: number; days: number } | null>(null)
const clearingCache = ref(false)
const restoring = ref(false)
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
    thumbCache.value = (await fetchThumbCache()).data
  } catch {
    // 普通情况只有超管会看到，取不到就隐藏
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

async function clearCache() {
  await ElMessageBox.confirm('清空后图库缩略图会按需重新生成，原图与图库记录不受影响。继续？', '清空缩略图缓存', {
    type: 'warning',
  })
  clearingCache.value = true
  try {
    const removed = (await clearThumbCache()).data
    ElMessage.success('已清理 ' + removed + ' 个缓存文件')
    thumbCache.value = (await fetchThumbCache()).data
  } catch {
    // 取消或失败
  } finally {
    clearingCache.value = false
  }
}

/** 导出元数据清单 */
async function doExport(format: 'csv' | 'json') {
  const blob = await exportManifest(format)
  saveBlob(blob, 'images-' + new Date().toISOString().slice(0, 10) + (format === 'json' ? '.json' : '.csv'))
}

/** 选择清单文件后回灌 */
async function onManifestFile(file: { raw?: File }) {
  const raw = file.raw
  if (!raw) {
    return
  }
  restoring.value = true
  try {
    const text = await raw.text()
    const items = raw.name.toLowerCase().endsWith('.json') ? parseManifestJson(text) : parseManifestCsv(text)
    if (items.length === 0) {
      ElMessage.warning('没有解析到任何记录，请确认文件是本系统导出的 CSV / JSON')
      return
    }
    await ElMessageBox.confirm(
      '将回灌 ' + items.length + ' 条元数据记录（只补库里没有的，不碰云端文件）。继续？',
      '从清单回灌',
      { type: 'warning' },
    )
    const result = (await importManifest(items)).data
    ElMessage.success('已插入 ' + result.inserted + ' 条、跳过 ' + result.skipped + ' 条')
  } catch {
    // 用户点取消与接口报错都由拦截器提示，这里不再重复弹错
  } finally {
    restoring.value = false
  }
}

function parseManifestJson(text: string): Record<string, unknown>[] {
  const parsed = JSON.parse(text) as unknown
  if (Array.isArray(parsed)) {
    return parsed as Record<string, unknown>[]
  }
  if (parsed && typeof parsed === 'object' && Array.isArray((parsed as { items?: unknown[] }).items)) {
    return (parsed as { items: Record<string, unknown>[] }).items
  }
  return []
}

/** 解析本系统导出的 CSV（带 BOM，字段内可含逗号与引号） */
function parseManifestCsv(text: string): Record<string, string>[] {
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter((line) => line.trim().length > 0)
  if (lines.length < 2) {
    return []
  }
  const header = splitCsvLine(lines[0])
  return lines.slice(1).map((line) => {
    const cells = splitCsvLine(line)
    const row: Record<string, string> = {}
    header.forEach((key, index) => {
      row[key] = cells[index] ?? ''
    })
    return row
  })
}

function splitCsvLine(line: string): string[] {
  const cells: string[] = []
  let current = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const char = line[i]
    if (inQuotes) {
      if (char === '"') {
        if (line[i + 1] === '"') {
          current += '"'
          i += 1
        } else {
          inQuotes = false
        }
      } else {
        current += char
      }
    } else if (char === '"') {
      inQuotes = true
    } else if (char === ',') {
      cells.push(current)
      current = ''
    } else {
      current += char
    }
  }
  cells.push(current)
  return cells
}

onMounted(load)
</script>
