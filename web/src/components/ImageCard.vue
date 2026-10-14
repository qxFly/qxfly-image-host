<template>
  <div
    class="image-card"
    :class="{ 'is-selected': selected }"
    :title="selected ? '点击取消选择（Shift 连选）' : '点击选择（Shift 连选）'"
    @click="onCardClick"
  >
    <div ref="thumbRef" class="image-card__thumb-wrap" @click.stop>
      <el-image
        v-if="src"
        class="image-card__thumb"
        :src="src"
        fit="cover"
        :preview-src-list="[image.previewUrl || image.url]"
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

      <div class="image-card__zoom-hint"><el-icon :size="14"><ZoomIn /></el-icon></div>

      <div class="image-card__check" :class="{ 'is-checked': selected }" @click.stop="onCheckClick">
        <el-icon :size="13"><Check /></el-icon>
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

    <div class="image-card__actions" @click.stop>
      <el-button size="small" type="primary" plain @click="emit('links', image, 'download')">下载</el-button>
      <el-button size="small" @click="emit('links', image, 'copy')">直链</el-button>
      <el-button size="small" @click="emit('links', image, 'markdown')">MD</el-button>
      <el-popconfirm
        v-if="canManage"
        title="删除后移入回收站，可在「回收站」里恢复或彻底删除"
        width="260"
        @confirm="emit('remove', image)"
      >
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
import type { ImageVO, LinkMode } from '@/types'

const props = defineProps<{
  image: ImageVO
  selected: boolean
  /** 超级管理员才显示「删除」（普通用户只能浏览查看下载） */
  canManage?: boolean
}>()

const emit = defineEmits<{
  (event: 'toggle', id: number, shift: boolean): void
  (event: 'remove', image: ImageVO): void
  /** 下载 / 直链 / MD 都会先弹出「选择线路」的框 */
  (event: 'links', image: ImageVO, mode: LinkMode): void
}>()

const thumbRef = ref<HTMLElement>()
// 缩略图优先；没有缩略图就用原图。取图走缓存 + 懒加载
// 预览走「预览默认 CDN」解析出来的地址
const { src } = useCachedImage(thumbRef, () => props.image.thumbUrl || props.image.previewUrl || props.image.url)

// 点卡片本体（文件名 / 目录 / 信息区）就是选择，Shift 用来连选；
// 图片本身留给预览、操作按钮留给各自的动作，所以那两处都 stop 掉了
function onCardClick(event: MouseEvent) {
  emit('toggle', props.image.id, event.shiftKey)
}

function onCheckClick(event: MouseEvent) {
  emit('toggle', props.image.id, event.shiftKey)
}
</script>

<style scoped>
.thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}

.image-card {
  border-radius: 12px;
  border-color: #eef0f4;
  cursor: pointer;
  transition: box-shadow 0.22s, transform 0.22s, border-color 0.22s;
}

.image-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 10px 26px rgba(31, 42, 68, 0.12);
}

.image-card.is-selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.18), 0 10px 24px rgba(64, 158, 255, 0.16);
}

.image-card__thumb-wrap {
  position: relative;
  overflow: hidden;
  line-height: 0;
}

.image-card__thumb {
  height: 160px;
  transition: transform 0.35s ease;
}

.image-card:hover .image-card__thumb {
  transform: scale(1.05);
}

/* 选择标记：默认低调可见（触屏也能看见），悬停变蓝，选中填满 */
.image-card__check {
  position: absolute;
  top: 8px;
  left: 8px;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(31, 42, 68, 0.16);
  color: #c0c4cc;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(31, 42, 68, 0.16);
  transition: background 0.18s, border-color 0.18s, color 0.18s, transform 0.18s;
}

.image-card:hover .image-card__check {
  color: var(--el-color-primary);
  border-color: rgba(64, 158, 255, 0.6);
}

.image-card__check.is-checked,
.image-card:hover .image-card__check.is-checked {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  color: #fff;
  transform: scale(1.06);
}

/* 提示这张图可以点开预览 */
.image-card__zoom-hint {
  position: absolute;
  right: 8px;
  bottom: 8px;
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(31, 42, 68, 0.55);
  color: #fff;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s;
}

.image-card:hover .image-card__zoom-hint {
  opacity: 1;
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

/* 卡片最窄只有 190px，四个按钮按默认内边距会挤成两行，这里让它们平分一行 */
.image-card__actions :deep(.el-button) {
  flex: 1;
  min-width: 0;
  padding: 5px 0;
}

.image-card__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
