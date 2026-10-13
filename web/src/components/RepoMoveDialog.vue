<template>
  <el-dialog v-model="visible" title="仓库目录整理（临时功能）" width="720px" top="7vh" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 14px">
      <template #title>
        只支持 GitHub。这个工具会在仓库里创建一个新提交，把源目录下的图片文件按系统的存储规则搬到目标目录。
        不是逐个下载再上传 —— 同仓库移动直接复用文件原有的 blob，所以几千个文件也只需要几次 API 调用、一个提交。
      </template>
    </el-alert>

    <el-form label-width="110px" :disabled="running">
      <el-form-item label="存储配置">
        <el-select v-model="form.storageConfigId" style="width: 100%">
          <el-option
            v-for="item in githubConfigs"
            :key="item.id"
            :label="item.name + ' · ' + (item.config.owner || '') + '/' + (item.config.repo || '')"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="源目录">
        <el-input v-model="form.sourceDirectory" placeholder="api" />
      </el-form-item>
      <el-form-item label="目标目录">
        <el-input v-model="form.targetDirectory" placeholder="api/images" />
      </el-form-item>
      <el-form-item label="范围">
        <el-checkbox v-model="form.includeSubdirectories">包含子目录</el-checkbox>
        <div class="text-muted" style="margin-top: 4px">
          不勾选时只处理<b>直接放在源目录下</b>的文件，子文件夹里的不动
        </div>
      </el-form-item>
      <el-form-item label="文件名">
        <el-checkbox v-model="form.keepFileName">保留原文件名</el-checkbox>
        <el-checkbox v-model="form.layered" style="margin-left: 16px">按 日期/分片 分层</el-checkbox>
      </el-form-item>

      <el-form-item label="存放规则">
        <div>
          <span class="text-muted">新路径：</span>
          <span class="mono">{{ pathPreview }}</span>
        </div>
        <div v-if="!form.layered" class="text-muted" style="margin-top: 4px">
          不勾选「分层」时所有文件会直接堆在目标目录下。几千个文件堆一层，既和「每个分片 500 个」的约定冲突，
          在 GitHub 上浏览也会很慢（Contents API 单目录只列前 1000 个）。
        </div>
        <div v-if="form.keepFileName" class="text-muted" style="margin-top: 4px">
          保留原文件名时，如果目标位置已经有同名文件，那个文件会被<b>跳过并单独列出来</b>，不会覆盖。
        </div>
      </el-form-item>
    </el-form>

    <div v-if="checks.length" class="checks">
      <div class="checks__head">
        <span>自检结果</span>
        <el-button link size="small" @click="checks = []">收起</el-button>
      </div>
      <div v-for="item in checks" :key="item.name" class="check">
        <el-tag size="small" :type="item.ok ? 'success' : 'danger'" effect="plain">{{ item.ok ? 'OK' : 'FAIL' }}</el-tag>
        <span class="check__name">{{ item.name }}</span>
        <span class="check__detail">{{ item.detail }}</span>
      </div>
    </div>

    <div v-if="result" class="result">
      <el-alert
        v-if="result.total === 0"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
        title="没有找到可移动的文件"
        :description="'解析出的源目录是 [' + (result.resolvedSourceDirectory || '') + ']。请确认它和仓库里的目录一致，不要带分支名（写成 api 而不是 main/api）。'"
      />
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="解析后的目录">
          <span class="mono">{{ result.resolvedSourceDirectory }} → {{ result.resolvedTargetDirectory }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="命中文件">{{ result.total }} 个</el-descriptions-item>
        <el-descriptions-item v-if="result.moved > 0" label="已移动">{{ result.moved }} 个</el-descriptions-item>
        <el-descriptions-item v-if="result.commits > 0" label="提交数">{{ result.commits }} 个</el-descriptions-item>
        <el-descriptions-item v-if="result.collisions > 0" label="重名跳过">
          <span class="warn">{{ result.collisions }} 个（目标位置已有同名文件）</span>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="result.collisionSamples && result.collisionSamples.length" style="margin-top: 8px">
        <div class="text-muted">被跳过的文件：</div>
        <div v-for="item in result.collisionSamples" :key="item" class="mono sample">{{ item }}</div>
      </div>
      <div v-if="result.shards && result.shards.length" style="margin-top: 8px">
        <span class="text-muted">分片分布：</span>
        <el-tag v-for="item in result.shards" :key="item" size="small" effect="plain" style="margin-right: 6px">
          {{ item }}
        </el-tag>
      </div>
      <div v-if="result.samples && result.samples.length" style="margin-top: 8px">
        <div class="text-muted">路径示例：</div>
        <div v-for="item in result.samples" :key="item" class="mono sample">{{ item }}</div>
      </div>
      <el-alert
        v-if="result.message"
        :type="result.moved > 0 ? 'success' : 'info'"
        :closable="false"
        style="margin-top: 10px"
        :title="result.message"
      />
      <div v-if="result.commitUrl" style="margin-top: 8px">
        <el-link type="primary" :href="result.commitUrl" target="_blank">{{ result.commitUrl }}</el-link>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ running ? '后台执行中' : '关闭' }}</el-button>
      <el-button :loading="checking" :disabled="!canRun" @click="doDiagnose">自检</el-button>
      <el-button :loading="previewing" :disabled="!canRun" @click="doPreview">预览</el-button>
      <el-popconfirm
        title="将在仓库里创建一个新提交并移动这些文件，确认执行？"
        width="280"
        @confirm="doExecute"
      >
        <template #reference>
          <el-button type="primary" :loading="running" :disabled="!canRun || result?.total === 0">执行移动</el-button>
        </template>
      </el-popconfirm>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { repoMoveDiagnose, repoMoveExecute, repoMovePreview } from '@/api/images'
import { fetchStorageConfigs } from '@/api/storage'
import type { RepoMoveCheckVO, RepoMoveResultVO } from '@/api/images'
import type { StorageConfigVO } from '@/types'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'finished'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const configs = ref<StorageConfigVO[]>([])
const result = ref<RepoMoveResultVO | null>(null)
const previewing = ref(false)
const running = ref(false)
const checking = ref(false)
const checks = ref<RepoMoveCheckVO[]>([])

const form = reactive({
  storageConfigId: undefined as number | undefined,
  sourceDirectory: 'api',
  targetDirectory: 'api/images',
  includeSubdirectories: false,
  keepFileName: true,
  layered: false,
})

/** 直观展示最终路径长什么样 */
const pathPreview = computed(() => {
  const dir = form.targetDirectory.replace(/\/+$/, '') + '/'
  const middle = form.layered ? '日期/分片/' : ''
  const name = form.keepFileName ? '原文件名.png' : 'a1b2c3d4….png'
  return dir + middle + name
})

const githubConfigs = computed(() => configs.value.filter((item) => item.type === 'GITHUB'))
const canRun = computed(() => !!form.storageConfigId && !!form.sourceDirectory && !!form.targetDirectory)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) {
      return
    }
    result.value = null
    try {
      configs.value = (await fetchStorageConfigs()).data
      const first = githubConfigs.value[0]
      if (first) {
        form.storageConfigId = first.id
        const prefix = (first.config && first.config.pathPrefix) || 'api'
        form.targetDirectory = prefix
        form.sourceDirectory = prefix.replace(/\/images$/, '') || 'api'
      }
    } catch {
      // 拦截器已提示
    }
  },
)

function payload() {
  return {
    storageConfigId: form.storageConfigId as number,
    sourceDirectory: form.sourceDirectory,
    targetDirectory: form.targetDirectory,
    includeSubdirectories: form.includeSubdirectories,
    keepFileName: form.keepFileName,
    layered: form.layered,
  }
}

/** 自检：把整条链路逐项验一遍，全只读 */
async function doDiagnose() {
  if (!form.storageConfigId) {
    return
  }
  checking.value = true
  try {
    checks.value = (await repoMoveDiagnose(payload())).data
  } catch {
    // 拦截器已提示
  } finally {
    checking.value = false
  }
}

async function doPreview() {
  if (!form.storageConfigId) {
    return
  }
  previewing.value = true
  try {
    const response = await repoMovePreview({
      storageConfigId: form.storageConfigId,
      sourceDirectory: form.sourceDirectory,
      targetDirectory: form.targetDirectory,
      includeSubdirectories: form.includeSubdirectories,
      keepFileName: form.keepFileName,
      layered: form.layered,
    })
    result.value = response.data
  } catch {
    // 拦截器已提示
  } finally {
    previewing.value = false
  }
}

async function doExecute() {
  if (!form.storageConfigId) {
    return
  }
  running.value = true
  try {
    const response = await repoMoveExecute({
      storageConfigId: form.storageConfigId,
      sourceDirectory: form.sourceDirectory,
      targetDirectory: form.targetDirectory,
      includeSubdirectories: form.includeSubdirectories,
      keepFileName: form.keepFileName,
      layered: form.layered,
    })
    result.value = response.data
    ElMessage.success('已移动 ' + response.data.moved + ' 个文件')
    emit('finished')
  } catch {
    // 拦截器已提示
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.result {
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}

.sample {
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}

.warn {
  color: #e6a23c;
}

.checks {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.checks__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}

.check {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
}

.check__name {
  flex: 0 0 110px;
  color: #303133;
}

.check__detail {
  flex: 1;
  color: #606266;
  word-break: break-all;
}
</style>
