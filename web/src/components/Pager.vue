<template>
  <div :class="['pager', centered ? 'pager--center' : '']">
    <el-pagination
      background
      :layout="layout"
      :total="total"
      :current-page="page"
      :page-size="pageSize"
      :page-sizes="sizes"
      :small="small"
      @current-change="(value: number) => emit('change', value, pageSize)"
      @size-change="(value: number) => emit('change', page, value)" />
  </div>
</template>

<script setup lang="ts">
/**
 * 统一分页条：主题相关的布局（prev/pager/next/sizes/jumper/total）只写一遍，
 * 尺寸样式由全局层统一控制。
 */
withDefaults(
  defineProps<{
    total: number
    page: number
    pageSize: number
    sizes?: number[]
    small?: boolean
    centered?: boolean
    layout?: string
  }>(),
  {
    sizes: () => [20, 50, 100, 200],
    small: false,
    centered: true,
    layout: 'prev, pager, next, sizes, jumper, total',
  },
)

const emit = defineEmits<{ (event: 'change', page: number, size: number): void }>()
</script>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  padding: 4px 0;
}

.pager--center {
  justify-content: center;
}
</style>
