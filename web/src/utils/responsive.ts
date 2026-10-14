import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 是否小屏（手机/窄窗口）。用 matchMedia 而不是 resize 监听，切换时只在断点变化时触发一次。
 */
export function useIsMobile(query = '(max-width: 820px)') {
  const matches = ref(false)
  let media: MediaQueryList | null = null

  function onChange(event: MediaQueryListEvent | MediaQueryList) {
    matches.value = event.matches
  }

  onMounted(() => {
    if (typeof window === 'undefined' || !window.matchMedia) {
      return
    }
    media = window.matchMedia(query)
    onChange(media)
    media.addEventListener('change', onChange)
  })

  onBeforeUnmount(() => {
    media?.removeEventListener('change', onChange)
  })

  return matches
}
