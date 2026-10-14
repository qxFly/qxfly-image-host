export function formatSize(bytes?: number | null): string {
  if (!bytes || bytes <= 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value = value / 1024
    index += 1
  }
  return (index === 0 ? value : value.toFixed(2)) + ' ' + units[index]
}

export function formatDate(value?: string | null): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 19)
}

/** 速度：字节/秒 -> 可读文本 */
export function formatSpeed(bytesPerSecond?: number | null): string {
  if (!bytesPerSecond || bytesPerSecond <= 0) {
    return '—'
  }
  return formatSize(bytesPerSecond) + '/s'
}

/** 剩余时间：秒 -> 可读文本；负数表示算不出来 */
export function formatDuration(seconds?: number | null): string {
  if (seconds === null || seconds === undefined || seconds < 0 || !Number.isFinite(seconds)) {
    return '—'
  }
  if (seconds < 60) {
    return Math.max(1, Math.round(seconds)) + ' 秒'
  }
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds % 60)
  if (minutes < 60) {
    return minutes + ' 分 ' + rest + ' 秒'
  }
  return Math.floor(minutes / 60) + ' 时 ' + (minutes % 60) + ' 分'
}

export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 降级到 execCommand
  }
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}
