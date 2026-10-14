<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">分享链接</h2>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar" style="margin: 0">
          <span>把选中的图片打包成公开相册，可选密码与有效期，随时可撤销</span>
          <div class="toolbar-spacer"></div>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-empty v-if="shares.length === 0" description="还没有分享链接：在图库里选中图片后点「分享」" :image-size="80" />

      <el-table v-else :data="shares" style="width: 100%">
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="图片数" width="90">
          <template #default="{ row }">{{ row.imageCount }}</template>
        </el-table-column>
        <el-table-column label="保护" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.passwordProtected" size="small" type="warning" effect="plain">需要密码</el-tag>
            <span v-else class="text-muted">公开</span>
            <el-tag v-if="row.expired" size="small" type="danger" effect="plain" style="margin-left: 4px">已过期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期" width="170">
          <template #default="{ row }">
            <span v-if="row.expiresAt">{{ row.expiresAt }}</span>
            <span v-else class="text-muted">长期</span>
          </template>
        </el-table-column>
        <el-table-column label="浏览" width="80">
          <template #default="{ row }">{{ row.viewCount || 0 }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" :fixed="isMobile ? undefined : 'right'">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="open(row)">打开</el-button>
            <el-button size="small" link type="primary" @click="copy(row)">复制链接</el-button>
            <el-button size="small" link type="danger" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useIsMobile } from '@/utils/responsive'
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteShare, fetchShares, type ShareVO } from '@/api/shares'
import { copyText } from '@/utils/format'

const isMobile = useIsMobile()
const loading = ref(false)
const shares = ref<ShareVO[]>([])

/** el-table 插槽行是宽松类型，这里声明一个够用的子集 */
interface ShareRow {
  id?: number
  url?: string
}

function fullUrl(row: ShareRow) {
  return window.location.origin + window.location.pathname + (row.url || '')
}

async function load() {
  loading.value = true
  try {
    shares.value = (await fetchShares()).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function open(row: ShareRow) {
  window.open(fullUrl(row), '_blank')
}

async function copy(row: ShareRow) {
  const ok = await copyText(fullUrl(row))
  ElMessage[ok ? 'success' : 'warning'](ok ? '链接已复制' : '复制失败，请手动复制')
}

async function revoke(row: ShareRow) {
  await ElMessageBox.confirm('撤销后这个链接立刻失效，已分享出去的人将打不开。继续？', '撤销分享', {
    type: 'warning',
  })
  if (row.id) {
    await deleteShare(row.id)
  }
  ElMessage.success('已撤销')
  await load()
}

onMounted(load)
</script>
