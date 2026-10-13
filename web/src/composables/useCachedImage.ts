import { onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { resolveImage } from '@/utils/imageCache'

/**
 * 带缓存 + 懒加载的图片地址。
 *
 * - 元素进入视口（提前 200px）才去取图，配合列表滚动不会一次性下载整页
 * - 取图走 IndexedDB 缓存（见 utils/imageCache.ts），返回 blob: 地址
 * - 组件卸载时释放 blob: 地址，避免内存泄漏
 */
export function useCachedImage(target: Ref<HTMLElement | undefined>, source: () => string | undefined) {
  const src = ref('')
  const failed = ref(false)
  let objectUrl: string | undefined
  let observer: IntersectionObserver | undefined
  let requesting: string | undefined

  function release() {
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
      objectUrl = undefined
    }
  }

  async function load() {
    const url = source()
    if (!url) {
      release()
      src.value = ''
      return
    }
    if (url === requesting && src.value) {
      return
    }
    requesting = url
    try {
      const resolved = await resolveImage(url)
      if (requesting !== url) {
        if (resolved.startsWith('blob:')) {
          URL.revokeObjectURL(resolved)
        }
        return
      }
      release()
      objectUrl = resolved.startsWith('blob:') ? resolved : undefined
      failed.value = false
      src.value = resolved
    } catch {
      failed.value = true
      src.value = url
    }
  }

  onMounted(() => {
    if (typeof IntersectionObserver === 'undefined' || !target.value) {
      load()
      return
    }
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          observer?.disconnect()
          observer = undefined
          load()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(target.value)
  })

  watch(source, () => {
    src.value = ''
    load()
  })

  onUnmounted(() => {
    observer?.disconnect()
    release()
  })

  return { src, failed }
}
