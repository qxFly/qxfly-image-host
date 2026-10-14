<template>
  <div class="share-page">
    <div class="share-shell">
      <div class="share-head">
        <h2 class="share-title">{{ share ? share.title : '分享相册' }}</h2>
        <span v-if="share" class="text-muted">
          共 {{ share.imageCount }} 张 · 浏览 {{ share.viewCount || 0 }} 次
          <template v-if="share.expiresAt"> · {{ share.expiresAt }} 过期</template>
        </span>
      </div>

      <el-card v-if="needPassword" shadow="never" class="share-card">
        <div class="text-muted" style="margin-bottom: 12px">这个相册需要访问密码</div>
        <el-input
          v-model="password"
          type="password"
          placeholder="请输入访问密码"
          show-password
          @keyup.enter="load"
        >
          <template #append>
            <el-button :loading="loading" @click="load">进入</el-button>
          </template>
        </el-input>
      </el-card>

      <el-card v-else-if="error" shadow="never" class="share-card">
        <el-empty :description="error" :image-size="90" />
      </el-card>

      <el-card v-else shadow="never">
        <div v-loading="loading" class="share-grid">
          <el-image
            v-for="image in images"
            :key="image.id"
            class="share-tile"
            :src="image.thumbUrl || image.previewUrl || image.url"
            :preview-src-list="previewList"
            :initial-index="indexOf(image)"
            fit="cover"
            preview-teleported
            hide-on-click-modal
            loading="lazy"
          >
            <template #error>
              <div class="share-tile share-tile--error">
                <el-icon><Picture /></el-icon>
              </div>
            </template>
          </el-image>
        </div>
        <el-empty v-if="!loading && images.length === 0" description="这个相册还没有图片" :image-size="80" />
      </el-card>

      <div class="share-foot text-muted">由 qxfly-image-host 提供</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { viewShare } from '@/api/shares'
import type { ShareVO } from '@/api/shares'
import type { ImageVO } from '@/types'

const route = useRoute()
const loading = ref(false)
const share = ref<ShareVO | null>(null)
const images = ref<ImageVO[]>([])
const password = ref('')
const needPassword = ref(false)
const error = ref('')

const previewList = computed(() =>
  images.value.map((image: ImageVO) => image.previewUrl || image.url || '').filter(Boolean),
)

function indexOf(image: ImageVO) {
  const url = image.previewUrl || image.url || ''
  return Math.max(0, previewList.value.indexOf(url))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await viewShare(String(route.params.token), password.value || undefined)
    share.value = result.data
    images.value = result.data.images || []
    needPassword.value = false
  } catch (e) {
    const message = e instanceof Error ? e.message : '打开失败'
    if (message.indexOf('密码') >= 0) {
      needPassword.value = true
      error.value = ''
    } else {
      // 分享不存在 / 已撤销 / 已过期
      error.value = message
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  background: #f6f7f9;
  padding: 24px 16px 48px;
}

.share-shell {
  max-width: 1180px;
  margin: 0 auto;
}

.share-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.share-title {
  margin: 0;
  font-size: 20px;
}

.share-card {
  max-width: 460px;
  margin: 0 auto;
}

.share-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  min-height: 60px;
}

.share-tile {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 8px;
  overflow: hidden;
  background: #f2f3f5;
}

.share-tile--error {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.share-foot {
  text-align: center;
  margin-top: 20px;
  font-size: 12px;
}
</style>
