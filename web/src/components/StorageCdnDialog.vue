<template>
  <el-dialog v-model="visible" :title="'CDN 线路 · ' + configName" width="1000px" top="5vh" @open="load">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="cdn-tip"
      title="可以为这个存储后端配置多条线路：内置预设（GitHub 的 jsDelivr / Statically 等）+ 自定义前缀。预览 / 下载 / 复制可以各选一条默认线路；延时是本机到该线路的往返耗时。注意 jsDelivr 这类 CDN 有缓存，刚上传的图片可能一时取不到，所以默认仍用原始地址。"
    />

    <div class="toolbar">
      <el-button :loading="testing" @click="testAll">
        <el-icon><Odometer /></el-icon>&nbsp;全部测速
      </el-button>
      <div class="toolbar-spacer"></div>
      <el-button type="primary" @click="openAdd">
        <el-icon><Plus /></el-icon>&nbsp;新增自定义 CDN
      </el-button>
    </div>

    <el-table v-loading="loading" :data="cdns" max-height="400">
      <el-table-column label="线路" min-width="180">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag size="small" effect="plain" class="ml">{{ row.origin ? '原始' : row.builtin ? '预设' : '自定义' }}</el-tag>
          <el-tag v-if="!row.enabled && !row.origin" size="small" type="info" effect="plain" class="ml">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="前缀模板" min-width="230">
        <template #default="{ row }">
          <span v-if="row.origin" class="text-muted">存储后端直链</span>
          <span v-else class="mono">{{ row.template || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="示例地址" min-width="230">
        <template #default="{ row }">
          <span class="mono cdn-url" :title="row.example || ''">{{ row.example || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="延时" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="latencyTagType(latency[row.key])">{{ latencyText(row.key) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前默认" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.previewDefault" size="small" type="success" effect="plain" class="mr">预览</el-tag>
          <el-tag v-if="row.downloadDefault" size="small" type="success" effect="plain" class="mr">下载</el-tag>
          <el-tag v-if="row.copyDefault" size="small" type="success" effect="plain" class="mr">复制</el-tag>
          <span v-if="!row.previewDefault && !row.downloadDefault && !row.copyDefault" class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="testOne(row)">测速</el-button>
          <template v-if="!row.builtin && !row.origin">
            <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除该线路？" width="200" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有可用线路" :image-size="70" />
      </template>
    </el-table>

    <div class="defaults">
      <span class="defaults__title">默认线路</span>
      <div class="defaults__row">
        <span class="text-muted">预览</span>
        <el-select v-model="defaults.preview" style="width: 210px">
          <el-option v-for="item in options" :key="item.key" :label="item.name" :value="item.key" />
        </el-select>
        <span class="text-muted">下载</span>
        <el-select v-model="defaults.download" style="width: 210px">
          <el-option v-for="item in options" :key="item.key" :label="item.name" :value="item.key" />
        </el-select>
        <span class="text-muted">复制</span>
        <el-select v-model="defaults.copy" style="width: 210px">
          <el-option v-for="item in options" :key="item.key" :label="item.name" :value="item.key" />
        </el-select>
        <el-button type="primary" :loading="saving" @click="saveDefaults">保存默认</el-button>
      </div>
      <div class="text-muted" style="margin-top: 6px">
        下载默认建议保持「原始地址」或「原站代理」；预览 / 复制可以选 CDN 加速，但要注意 CDN 缓存。
      </div>
    </div>

    <el-dialog v-model="formVisible" :title="editingKey ? '编辑自定义 CDN' : '新增自定义 CDN'" width="580px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="86px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：我的加速域名" />
        </el-form-item>
        <el-form-item label="地址前缀" prop="template">
          <el-input v-model="form.template" placeholder="https://cdn.example.com" />
          <div class="text-muted" style="margin-top: 4px">
            可用 {owner} / {repo} / {branch} 占位符，例如 https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSave">保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createCdn, deleteCdn, fetchCdns, setCdnDefaults, updateCdn } from '@/api/storage'
import { latencyTagType, measureLatency } from '@/utils/latency'
import type { CdnVO, StorageConfigVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  config: StorageConfigVO | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const ORIGIN = 'origin'

const loading = ref(false)
const testing = ref(false)
const saving = ref(false)
const cdns = ref<CdnVO[]>([])
const latency = ref<Record<string, number | null | undefined>>({})
const defaults = reactive({ preview: ORIGIN, download: ORIGIN, copy: ORIGIN })

const formVisible = ref(false)
const editingKey = ref('')
const formRef = ref<FormInstance>()
const form = reactive({ name: '', template: '', enabled: true })
const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  template: [
    { required: true, message: '请输入地址前缀', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: '必须以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

/** el-table 插槽行是宽松类型 */
interface CdnRow {
  key?: string
  name?: string
  template?: string
  example?: string | null
  origin?: boolean
  builtin?: boolean
  enabled?: boolean
}

const configName = computed(() => (props.config ? props.config.name : ''))
/** 默认线路只能从「原始地址 + 已启用的线路」里选 */
const options = computed(() => cdns.value.filter((item) => item.origin || item.enabled))

function latencyText(key: string) {
  const value = latency.value[key]
  if (value === undefined) {
    return '未测'
  }
  return value === null ? '不可达' : value + ' ms'
}

function apply(list: CdnVO[]) {
  cdns.value = list
  const pick = (purpose: 'previewDefault' | 'downloadDefault' | 'copyDefault') =>
    list.find((item) => item[purpose])?.key || ORIGIN
  defaults.preview = pick('previewDefault')
  defaults.download = pick('downloadDefault')
  defaults.copy = pick('copyDefault')
}

async function load() {
  if (!props.config) {
    return
  }
  loading.value = true
  latency.value = {}
  try {
    apply((await fetchCdns(props.config.id)).data)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function testAll() {
  testing.value = true
  latency.value = {}
  for (const item of cdns.value) {
    if (!item.example) {
      continue
    }
    latency.value = { ...latency.value, [item.key]: undefined }
    const ms = await measureLatency(item.example)
    latency.value = { ...latency.value, [item.key]: ms }
  }
  testing.value = false
}

async function testOne(row: CdnRow) {
  const key = row.key
  if (!key || !row.example) {
    return
  }
  latency.value = { ...latency.value, [key]: undefined }
  const ms = await measureLatency(row.example)
  latency.value = { ...latency.value, [key]: ms }
}

async function saveDefaults() {
  if (!props.config) {
    return
  }
  saving.value = true
  try {
    const toValue = (key: string) => (key === ORIGIN ? null : key)
    apply((await setCdnDefaults(props.config.id, {
      preview: toValue(defaults.preview),
      download: toValue(defaults.download),
      copy: toValue(defaults.copy),
    })).data)
    ElMessage.success('默认线路已保存')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

function openAdd() {
  editingKey.value = ''
  form.name = ''
  form.template = ''
  form.enabled = true
  formVisible.value = true
}

function openEdit(row: CdnRow) {
  editingKey.value = row.key || ''
  form.name = row.name || ''
  form.template = row.template || ''
  form.enabled = row.enabled !== false
  formVisible.value = true
}

async function doSave() {
  if (!props.config || !formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    const payload = { name: form.name, template: form.template, enabled: form.enabled }
    const result = editingKey.value
      ? await updateCdn(props.config.id, editingKey.value, payload)
      : await createCdn(props.config.id, payload)
    apply(result.data)
    formVisible.value = false
    ElMessage.success(editingKey.value ? '已保存' : '已添加，记得在下面选择默认线路')
    await testAll()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function remove(row: CdnRow) {
  if (!props.config || !row.key) {
    return
  }
  if (!props.config) {
    return
  }
  try {
    apply((await deleteCdn(props.config.id, row.key)).data)
    ElMessage.success('已删除')
  } catch {
    // 拦截器已提示
  }
}
</script>

<style scoped>
.cdn-tip {
  margin-bottom: 12px;
}

.cdn-tip :deep(.el-alert__title) {
  line-height: 1.6;
}

.cdn-url {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.defaults {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  background: #fafbfc;
}

.defaults__title {
  font-size: 13px;
  font-weight: 600;
}

.defaults__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.ml {
  margin-left: 6px;
}

.mr {
  margin-right: 4px;
}
</style>
