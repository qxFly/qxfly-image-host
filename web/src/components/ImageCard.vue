<template>
  <div class="image-card" :class="{ 'image-card--selected': selected }">
    <div ref="thumbRef" class="image-card__thumb-wrap">
      <el-checkbox
        class="image-card__check"
        :model-value="selected"
        @change="emit('toggle', image.id)"
      />
      <el-image
        v-if="src"
        class="image-card__thumb"
        :src="src"
        fit="cover"
        :preview-src-list="[image.url]"
        preview-teleported
        hide-on-click-modal
      >
        <template #error>
          <div class="image-card__thumb thumb-fallback">
            <el-icon :size="22" color="#c0c4cc"><Picture /></el-icon>
          </div>
        </template>
      </el-image>
      <div v-else class="image-card__thumb thumb-fallback">
        <el-icon :size="18" color="#c0c4cc"><Loading /></el-icon>
      </div>
    </div>

    <div class="image-card__body">
      <div class="image-card__name" :title="image.originalName">{{ image.originalName }}</div>
      <div class="image-card__dir" :title="image.directory || '（根目录）'">
        <el-icon :size="12"><FolderOpened /></el-icon>
        <span>{{ image.directory || '（根目录）' }}</span>
      </div>
      <div class="image-card__meta">
        <el-tag size="small" effect="plain">{{ image.storageTypeLabel }}</el-tag>
        <span>{{ formatSize(image.size) }}</span>
      </div>
    </div>

    <div class="image-card__actions">
      <el-button size="small" @click="emit('copy', image.url, '图片直链')">直链</el-button>
      <el-button size="small" @click="emit('copy', image.markdown, 'Markdown')">MD</el-button>
      <el-popconfirm title="将同时删除云端文件与本地记录，确认删除？" width="240" @confirm="emit('remove', image)">
        <template #reference>
          <el-button size="small" type="danger" plain>删除</el-button>
        </template>
      </el-popconfirm>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useCachedImage } from '@/composables/useCachedImage'
import { formatSize } from '@/utils/format'
import type { ImageVO } from '@/types'

const props = defineProps<{
  image: ImageVO
  selected: boolean
}>()

const emit = defineEmits<{
  (event: 'toggle', id: number): void
  (event: 'remove', image: ImageVO): void
  (event: 'copy', text: string, label: string): void
}>()

const thumbRef = ref<HTMLElement>()
// 缩略图优先；没有缩略图就用原图。取图走缓存 + 懒加载
const { src } = useCachedImage(thumbRef, () => props.image.thumbUrl || props.image.url)
</script>

<style scoped>
.thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}

.image-card__dir {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  font-size: 11px;
  color: #a8abb2;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
