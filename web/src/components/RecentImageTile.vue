<template>
  <div class="recent-tile">
    <div ref="thumbRef" class="recent-tile__thumb">
      <el-image
        v-if="src"
        class="recent-tile__image"
        :src="src"
        :alt="image.originalName || '图片'"
        fit="cover"
        :preview-src-list="[image.previewUrl || image.url]"
        preview-teleported
        hide-on-click-modal
      >
        <template #error>
          <div class="recent-tile__image thumb-fallback">
            <el-icon :size="18" color="#c0c4cc"><Picture /></el-icon>
          </div>
        </template>
      </el-image>
      <div v-else class="recent-tile__image thumb-fallback">
        <el-icon :size="16" color="#c0c4cc"><Loading /></el-icon>
      </div>

      <!-- 操作浮层压在图片上，不额外占高度，所以内容密度不受影响 -->
      <div class="recent-tile__actions">
        <el-tooltip content="下载（可选线路）" placement="top">
          <el-button size="small" circle @click="emit('links', image, 'download')">
            <el-icon><Download /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip content="复制直链（可选线路）" placement="top">
          <el-button size="small" circle @click="emit('links', image, 'copy')">
            <el-icon><Link /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip content="复制 Markdown（可选线路）" placement="top">
          <el-button size="small" circle @click="emit('links', image, 'markdown')">
            <el-icon><Document /></el-icon>
          </el-button>
        </el-tooltip>
        <el-popconfirm
          v-if="canManage"
          title="删除后移入回收站，可在图库的「回收站」里恢复或彻底删除"
          width="270"
          @confirm="emit('remove', image)"
        >
          <template #reference>
            <el-button size="small" circle type="danger">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-popconfirm>
      </div>
    </div>

    <div class="recent-tile__body" :title="tooltip">
      <div class="recent-tile__name">{{ image.originalName }}</div>
      <div class="recent-tile__meta">
        <span>{{ timeText }}</span>
        <span>{{ formatSize(image.size) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useCachedImage } from '@/composables/useCachedImage'
import { formatSize } from '@/utils/format'
import type { ImageVO, LinkMode } from '@/types'

const props = defineProps<{
  image: ImageVO
  /** 超级管理员才显示「删除」 */
  canManage?: boolean
}>()

const emit = defineEmits<{
  (event: 'links', image: ImageVO, mode: LinkMode): void
  (event: 'remove', image: ImageVO): void
}>()

const thumbRef = ref<HTMLElement>()
// 预览走「预览默认 CDN」解析出来的地址
const { src } = useCachedImage(thumbRef, () => props.image.thumbUrl || props.image.previewUrl || props.image.url)

/** createdAt 形如 2026-10-13T01:57:33，格子里只显示时分 */
const timeText = computed(() => (props.image.createdAt || '').replace('T', ' ').slice(11, 16) || '--:--')

/** 格子里放不下的信息（完整文件名、目录、存储、上传时间）走原生 tooltip */
const tooltip = computed(() => {
  const uploaded = (props.image.createdAt || '').replace('T', ' ').slice(0, 19)
  return [
    props.image.originalName,
    '目录：' + (props.image.directory || '（根目录）'),
    '存储：' + props.image.storageTypeLabel,
    '大小：' + formatSize(props.image.size) + '　上传：' + (uploaded || '-'),
  ].join('\n')
})
</script>

<style scoped>
.thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}

.recent-tile {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  overflow: hidden;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
}

.recent-tile:hover {
  transform: translateY(-2px);
  border-color: #e3e8f0;
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.06);
}

.recent-tile__thumb {
  position: relative;
  height: 100px;
  background: #f0f2f5;
  cursor: zoom-in;
}

.recent-tile__image {
  width: 100%;
  height: 100%;
  display: block;
}

.recent-tile__actions {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  justify-content: center;
  gap: 4px;
  padding: 14px 6px 6px;
  background: linear-gradient(to bottom, rgba(15, 20, 32, 0), rgba(15, 20, 32, 0.78));
  opacity: 0;
  transform: translateY(6px);
  /* 隐藏时必须让出点击，否则缩略图下缘的隐形按钮会吃掉预览点击 */
  pointer-events: none;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.recent-tile:hover .recent-tile__actions,
.recent-tile:focus-within .recent-tile__actions,
.recent-tile.is-busy .recent-tile__actions {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

.recent-tile__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.recent-tile__body {
  padding: 6px 8px 8px;
}

.recent-tile__name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-tile__meta {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  margin-top: 2px;
  font-size: 11px;
  color: #a8abb2;
}
</style>
