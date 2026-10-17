<template>
  <div class="page" v-loading="loading">
    <PageHeader title="分享链接" subtitle="把选中的图片打包成公开相册，可选密码与有效期，随时可撤销">
      <el-button :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <el-card shadow="never">

      <ListSkeleton v-if="loading && shares.length === 0" :rows="4" />

      <EmptyState
        v-else-if="shares.length === 0"
        icon="Share"
        title="还没有分享链接"
        description="在图库里勾选图片 → 「分享」即可生成对外相册（可加密码与有效期）"
        action-text="去图库"
        @action="router.push('/gallery')" />

      <TableCardList
        v-else-if="isMobile"
        :rows="shares"
        :fields="cardFields"
        empty-text="还没有分享链接"
        empty-description="在图库里勾选图片后点「分享」即可生成对外相册"
        empty-icon="Share"
      >
        <template #field-passwordProtected="{ row }">
          <el-tag v-if="row.passwordProtected" size="small" type="warning" effect="plain">需要密码</el-tag>
          <span v-else class="text-muted">公开</span>
          <el-tag v-if="row.expired" size="small" type="danger" effect="plain" style="margin-left: 4px">已过期</el-tag>
        </template>
        <template #field-expiresAt="{ row }">
          <span v-if="row.expiresAt">{{ row.expiresAt }}</span>
          <span v-else class="text-muted">长期</span>
        </template>
        <template #actions="{ row }">
          <el-button size="small" link type="primary" @click="open(row)">打开</el-button>
          <el-button size="small" link type="primary" @click="copy(row)">复制链接</el-button>
          <el-button size="small" link type="primary" @click="showViews(row)">明细</el-button>
          <el-button size="small" link type="danger" @click="revoke(row)">撤销</el-button>
        </template>
      </TableCardList>

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
            <el-button size="small" link type="primary" @click="showViews(row)">明细</el-button>
            <el-button size="small" link type="danger" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
<el-dialog v-model="viewsVisible" :title="'访问明细：' + (viewsTitle || '')" width="620px">
  <el-table v-loading="viewsLoading" :data="viewRows" size="small" max-height="420">
    <el-table-column label="时间" width="170">
      <template #default="{ row }">{{ row.createdAt }}</template>
    </el-table-column>
    <el-table-column label="来源 IP" width="150">
      <template #default="{ row }"><span class="mono">{{ row.ip || '-' }}</span></template>
    </el-table-column>
    <el-table-column label="User-Agent" min-width="240" show-overflow-tooltip>
      <template #default="{ row }">{{ row.userAgent || '-' }}</template>
    </el-table-column>
  </el-table>
  <div class="text-muted" style="margin-top: 8px">最多保留最近 200 条访问明细</div>
</el-dialog>


  </div>
</template>

<script setup lang="ts">
import { useIsMobile } from '@/utils/responsive'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ListSkeleton from '@/components/ListSkeleton.vue'
import TableCardList from '@/components/TableCardList.vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteShare, fetchShareViews, fetchShares, type ShareVO } from '@/api/shares'
import { copyText } from '@/utils/format'

const isMobile = useIsMobile()
const router = useRouter()

/** 手机端卡片模式字段 */
const cardFields = [
  { label: '标题', prop: 'title' },
  { label: '图片数', prop: 'imageCount' },
  { label: '保护', prop: 'passwordProtected' },
  { label: '有效期', prop: 'expiresAt' },
  { label: '浏览', prop: 'viewCount' },
]
interface ShareViewRow {
  id: number
  shareId: number
  ip?: string
  userAgent?: string
  createdAt: string
}

const viewsVisible = ref(false)
const viewsLoading = ref(false)
const viewsTitle = ref('')
const viewRows = ref<ShareViewRow[]>([])

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

/** 查看访问明细（谁在什么时候打开过） */
async function showViews(row: ShareRow) {
  if (!row.id) {
    return
  }
  viewsVisible.value = true
  viewsLoading.value = true
  viewsTitle.value = row.url ? row.url.replace('#/s/', '').slice(0, 8) + '…' : ''
  try {
    viewRows.value = (await fetchShareViews(row.id)).data
  } catch {
    // 拦截器已提示
  } finally {
    viewsLoading.value = false
  }
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
