<template>
  <div class="list-skeleton" aria-busy="true" aria-label="正在加载">
    <div v-for="row in rows" :key="row" class="list-skeleton__row">
      <div class="list-skeleton__thumb" />
      <div class="list-skeleton__body">
        <div class="list-skeleton__line is-title" />
        <div class="list-skeleton__line is-sub" />
      </div>
      <div class="list-skeleton__action" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 列表首屏骨架屏：接口返回前先占位，避免整页白屏。
 * 颜色沿用日系清新风的浅灰阶（无阴影，纯色块 + 微弱过渡）。
 */
withDefaults(defineProps<{ rows?: number }>(), { rows: 5 })
</script>

<style scoped>
.list-skeleton {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 0;
}

.list-skeleton__row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.list-skeleton__thumb {
  width: 92px;
  height: 56px;
  border-radius: 10px;
  background: #eef1f4;
  flex: 0 0 auto;
}

.list-skeleton__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.list-skeleton__line {
  height: 12px;
  border-radius: 6px;
  background: #eef1f4;
  animation: skeleton-pulse 1.4s ease-in-out infinite;
}

.list-skeleton__line.is-title {
  width: 46%;
}

.list-skeleton__line.is-sub {
  width: 26%;
  background: #f3f5f7;
}

.list-skeleton__action {
  width: 74px;
  height: 26px;
  border-radius: 8px;
  background: #f3f5f7;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.55;
  }
}

@media (prefers-reduced-motion: reduce) {
  .list-skeleton__line {
    animation: none;
  }
}

@media (max-width: 820px) {
  .list-skeleton__thumb {
    width: 64px;
    height: 44px;
  }
}
</style>
