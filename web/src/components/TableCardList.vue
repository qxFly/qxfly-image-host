<template>
  <div v-loading="loading" class="table-cards">
    <EmptyState v-if="!loading && rows.length === 0" :icon="emptyIcon" :title="emptyText" :description="emptyDescription" />
    <div v-for="row in rows" :key="String(row[idKey])" class="table-card">
      <div class="table-card__fields">
        <div v-for="field in fields" :key="field.label" class="table-card__field">
          <span class="table-card__label">{{ field.label }}</span>
          <span class="table-card__value">
            <slot :name="'field-' + field.prop" :row="row">
              {{ displayValue(row[field.prop]) }}
            </slot>
          </span>
        </div>
      </div>
      <div v-if="$slots.actions" class="table-card__actions">
        <slot name="actions" :row="row" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 手机端的表格替身：把一行记录变成一张卡片（标签 + 值 + 操作区）。
 *
 * 目的是解决窄屏下表格"只能横向滚动、操作列占满屏幕"的问题：
 * 数据与 el-table 同源，只是形态随断点切换（U4）。
 */
withDefaults(
  defineProps<{
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- 与 el-table 插槽一致，行内容由调用方定义
    rows: Record<string, any>[]
    fields: { label: string; prop: string }[]
    idKey?: string
    loading?: boolean
    emptyText?: string
    emptyDescription?: string
    emptyIcon?: string
  }>(),
  {
    idKey: 'id',
    loading: false,
    emptyText: '暂无数据',
    emptyDescription: '',
    emptyIcon: 'FolderOpened',
  },
)

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return String(value)
}
</script>

<style scoped>
.table-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-card {
  border: var(--jf-hair, 1px solid rgba(212, 212, 207, 0.4));
  border-radius: 12px;
  background: #fff;
  padding: 12px 14px;
}

.table-card__fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.table-card__field {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 13px;
}

.table-card__label {
  flex: 0 0 84px;
  color: var(--jf-text-2, #5a6b80);
}

.table-card__value {
  flex: 1;
  min-width: 0;
  color: var(--jf-text, #2f3a4a);
  word-break: break-all;
}

.table-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: var(--jf-hair, 1px solid rgba(212, 212, 207, 0.4));
}

.table-card__actions :deep(.el-button) {
  margin: 0;
}
</style>
