import { ref } from 'vue'

/**
 * 列表页的分页状态：把 "page / size / total + 翻页回调" 这套样板收敛成一个组合式函数。
 *
 * 用法：
 *   const pager = usePager()
 *   async function load() {
 *     const res = await fetchXxx({ page: pager.page.value, size: pager.size.value })
 *     pager.total.value = res.data.total
 *   }
 *   // 模板：<Pager :total="pager.total.value" ... @change="pager.onChange(load)" />
 */
export function usePager(initialSize = 20) {
  const page = ref(1)
  const size = ref(initialSize)
  const total = ref(0)

  /** 交给 Pager 的 @change：换了页或每页条数就回到第一页重新加载 */
  function onChange(load: (page?: number) => void | Promise<void>) {
    return (nextPage: number, nextSize: number) => {
      page.value = nextPage
      size.value = nextSize
      void load(1)
    }
  }

  /** 重置到第一页（筛选条件变化时用） */
  function reset() {
    page.value = 1
  }

  return { page, size, total, onChange, reset }
}
