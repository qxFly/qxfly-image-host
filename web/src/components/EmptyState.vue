<template>
  <div class="empty-state">
    <div class="empty-state__icon">
      <el-icon :size="34"><component :is="icon" /></el-icon>
    </div>
    <div class="empty-state__title">{{ title }}</div>
    <div v-if="description" class="empty-state__desc">{{ description }}</div>
    <el-button v-if="actionText" type="primary" plain @click="emit('action')">{{ actionText }}</el-button>
    <slot />
  </div>
</template>

<script setup lang="ts">
/**
 * 统一的空状态：图标 + 标题 + 说明 + 一个主操作。
 *
 * 之前 12 处 el-empty 的尺寸（70/80/90）、文案长度、是否有操作按钮都不一致，
 * 这里统一成「柔和图标块 + 标题 + 灰色说明 + 可选操作」，并保持日系清新风的留白节奏。
 */
withDefaults(
  defineProps<{
    icon?: string
    title: string
    description?: string
    actionText?: string
  }>(),
  { icon: 'FolderOpened', description: '', actionText: '' },
)

const emit = defineEmits<{ (event: 'action'): void }>()
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px 16px 44px;
  text-align: center;
}

.empty-state__icon {
  width: 68px;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #f2f6fb;
  color: var(--jf-sky-ink, #3d8fd1);
}

.empty-state__title {
  font-size: 14px;
  color: var(--jf-text, #2f3a4a);
}

.empty-state__desc {
  font-size: 13px;
  color: var(--jf-text-2, #5a6b80);
  max-width: 420px;
  line-height: 1.7;
}

@media (max-width: 820px) {
  .empty-state {
    padding: 28px 12px 32px;
  }
}
</style>
