import { get } from './http'
import type { SystemInfo } from '@/types'

export const fetchSystemInfo = () => get<SystemInfo>('/system/info')
