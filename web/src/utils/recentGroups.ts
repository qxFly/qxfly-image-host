import type { ImageVO } from '@/types'

export interface ImageDayGroup {
  /** yyyy-MM-dd */
  date: string
  /** 时间轴上的分组标题：今天 / 昨天 / 10-12 · 周一 */
  label: string
  items: ImageVO[]
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 本地时区的 yyyy-MM-dd，offsetDays 用来算「昨天」 */
export function dateKey(today: Date, offsetDays = 0): string {
  const date = new Date(today.getTime())
  date.setDate(date.getDate() - offsetDays)
  const pad = (value: number) => String(value).padStart(2, '0')
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
}

/** 把 yyyy-MM-dd 变成一个好读的标题；不是今天的就带上星期 */
export function dayLabel(date: string, today: Date = new Date()): string {
  if (date === dateKey(today, 0)) {
    return '今天'
  }
  if (date === dateKey(today, 1)) {
    return '昨天'
  }
  const parsed = new Date(date + 'T00:00:00')
  if (Number.isNaN(parsed.getTime())) {
    return date
  }
  return date.slice(5) + ' · ' + WEEKDAYS[parsed.getDay()]
}

/** createdAt 形如 2026-10-13T01:57:33 或 2026-10-13 01:57:33，取日期部分 */
export function dayOf(createdAt?: string | null): string {
  return (createdAt || '').slice(0, 10) || '未知日期'
}

/**
 * 按自然日分组。接口已经按时间倒序返回，所以顺着遍历用 Map 归并，
 * 既保证「日期倒序 + 组内时间倒序」，也不会出现同一个日期被拆成两组。
 */
export function groupImagesByDay(images: ImageVO[], today: Date = new Date()): ImageDayGroup[] {
  const groups: ImageDayGroup[] = []
  const byDate = new Map<string, ImageDayGroup>()
  for (const image of images) {
    const date = dayOf(image.createdAt)
    let group = byDate.get(date)
    if (!group) {
      group = { date, label: dayLabel(date, today), items: [] }
      byDate.set(date, group)
      groups.push(group)
    }
    group.items.push(image)
  }
  return groups
}
