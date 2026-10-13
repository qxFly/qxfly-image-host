<template>
  <el-dialog
    v-model="visible"
    :title="editing ? '编辑存储配置' : '新增存储配置'"
    width="660px"
    :close-on-click-modal="false"
    top="6vh"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
      <el-form-item label="配置名称" prop="name">
        <el-input v-model="form.name" placeholder="例如：阿里云 OSS 主账号" maxlength="64" />
      </el-form-item>

      <el-form-item label="存储类型" prop="type">
        <el-select v-model="form.type" :disabled="!!editing" style="width: 100%" @change="onTypeChange">
          <el-option
            v-for="item in types"
            :key="item.name"
            :label="item.label + ' · ' + item.shortLabel"
            :value="item.name"
          />
        </el-select>
        <div v-if="currentType" class="text-muted" style="margin-top: 4px; line-height: 1.5">
          {{ currentType.description }}
        </div>
      </el-form-item>

      <el-divider content-position="left">连接参数</el-divider>

      <el-form-item
        v-for="field in fields"
        :key="field.key"
        :label="field.label"
        :required="field.required"
        :error="errors[field.key]"
      >
        <el-select v-if="field.type === 'select'" v-model="form.config[field.key]" style="width: 100%">
          <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
        </el-select>

        <el-switch
          v-else-if="field.type === 'switch'"
          v-model="form.config[field.key]"
          active-value="true"
          inactive-value="false"
        />

        <el-input
          v-else-if="field.type === 'password'"
          v-model="form.config[field.key]"
          type="password"
          show-password
          :placeholder="placeholderOf(field)"
        />

        <el-input v-else v-model="form.config[field.key]" :placeholder="placeholderOf(field)" />

        <div v-if="field.help" class="text-muted" style="margin-top: 4px; line-height: 1.5">
          {{ field.help }}
        </div>
      </el-form-item>

      <el-divider content-position="left">其它</el-divider>

      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="设为默认">
        <el-switch v-model="form.isDefault" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" placeholder="选填" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="testing" @click="doTest">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createStorageConfig, testStorageConfigDraft, updateStorageConfig } from '@/api/storage'
import type { StorageConfigVO, StorageField, StorageTypeVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  types: StorageTypeVO[]
  editing: StorageConfigVO | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'saved'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const formRef = ref<FormInstance>()
const saving = ref(false)
const testing = ref(false)
const errors = reactive<Record<string, string>>({})

const form = reactive<{
  name: string
  type: string
  config: Record<string, string>
  enabled: boolean
  isDefault: boolean
  remark: string
}>({
  name: '',
  type: '',
  config: {},
  enabled: true,
  isDefault: false,
  remark: '',
})

const currentType = computed(() => props.types.find((item) => item.name === form.type) || null)
const fields = computed(() => (currentType.value ? currentType.value.fields : []))

const rules: FormRules = {
  name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择存储类型', trigger: 'change' }],
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reset()
    }
  },
)

function reset() {
  Object.keys(errors).forEach((key) => delete errors[key])
  if (props.editing) {
    form.name = props.editing.name
    form.type = props.editing.type
    form.config = { ...props.editing.config }
    form.enabled = props.editing.enabled
    form.isDefault = props.editing.isDefault
    form.remark = props.editing.remark || ''
  } else {
    form.name = ''
    form.type = props.types.length ? props.types[0].name : ''
    form.config = {}
    form.enabled = true
    form.isDefault = false
    form.remark = ''
    applyDefaults()
  }
}

function applyDefaults() {
  if (!currentType.value) {
    return
  }
  currentType.value.fields.forEach((field) => {
    const value = form.config[field.key]
    if (value === undefined || value === null) {
      form.config[field.key] = field.defaultValue === undefined ? '' : field.defaultValue
    }
  })
}

function onTypeChange() {
  form.config = {}
  applyDefaults()
}

function placeholderOf(field: StorageField) {
  if (field.placeholder) {
    return field.placeholder
  }
  if (field.secret && props.editing) {
    return '保持 ****** 表示不修改'
  }
  return '请输入' + field.label
}

function payload() {
  return {
    name: form.name,
    type: form.type,
    config: { ...form.config },
    isDefault: form.isDefault,
    enabled: form.enabled,
    remark: form.remark,
  }
}

/** 前端先做一次必填校验，减少无意义的网络请求 */
function checkRequired(): boolean {
  Object.keys(errors).forEach((key) => delete errors[key])
  let ok = true
  fields.value.forEach((field) => {
    if (!field.required) {
      return
    }
    const value = form.config[field.key]
    const isSecretKept = field.secret && props.editing && value === '******'
    if (!isSecretKept && (value === undefined || value === null || String(value).trim() === '')) {
      errors[field.key] = field.label + '不能为空'
      ok = false
    }
  })
  if (!ok) {
    ElMessage.warning('请先补全必填参数')
  }
  return ok
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !checkRequired()) {
    return
  }
  saving.value = true
  try {
    if (props.editing) {
      await updateStorageConfig(props.editing.id, payload())
    } else {
      await createStorageConfig(payload())
    }
    ElMessage.success('保存成功')
    emit('saved')
    visible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function doTest() {
  if (!checkRequired()) {
    return
  }
  testing.value = true
  try {
    const result = await testStorageConfigDraft(payload(), props.editing ? props.editing.id : undefined)
    ElMessage.success(result.data.message || '连接成功')
  } catch {
    // 拦截器已提示失败原因
  } finally {
    testing.value = false
  }
}
</script>
