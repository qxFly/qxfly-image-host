import { get } from './http'
import type { SystemInfo } from '@/types'

export const fetchSystemInfo = () => get<SystemInfo>('/system/info')

/** 概览页「待处理」提示（有问题时才返回条目） */
export const fetchAlerts = () =>
  get<{ level: 'info' | 'warning' | 'danger'; title: string; detail: string; path: string }[]>('/system/alerts')
