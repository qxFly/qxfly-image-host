<template>
  <div class="page-header">
    <div class="page-header__main">
      <h2 class="page-title" style="margin: 0">{{ title }}</h2>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions || $slots.default" class="page-header__side">
      <slot />
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 页面头部：标题 + 副标题 + 右侧操作区。
 *
 * 之前 9 个页面各写一份 "h2.page-title + toolbar"，间距与按钮顺序都不一致；
 * 统一成这个组件后，新增页面直接复用。
 */
withDefaults(
  defineProps<{
    title: string
    subtitle?: string
  }>(),
  { subtitle: '' },
)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: var(--jf-gap, 18px);
  flex-wrap: wrap;
}

.page-header__main {
  min-width: 0;
}

.page-header__side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--jf-text-2, #5a6b80);
}
</style>
