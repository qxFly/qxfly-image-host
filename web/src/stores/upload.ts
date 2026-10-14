import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { uploadImageWithProgress } from '@/api/images'

export type UploadStatus = 'pending' | 'uploading' | 'success' | 'failed' | 'canceled'

export interface UploadTask {
  id: string
  name: string
  size: number
  type: string
  status: UploadStatus
  /** 已上传字节数 */
  loaded: number
  /** 0 - 100 */
  progress: number
  /** 字节/秒 */
  speed: number
  /** 预计剩余秒数，-1 表示暂时算不出来 */
  eta: number
  /** 上传时选的存储后端（重试时沿用） */
  storageConfigId?: number
  error?: string
  imageId?: number
  /** 存储后端原始直链 */
  url?: string
  /** 按「预览默认线路」解析出来的地址 */
  previewUrl?: string
  /** 按「复制默认线路」解析出来的地址 */
  copyUrl?: string
  markdown?: string
  storageTypeLabel?: string
  createdAt: number
  finishedAt?: number
}

/** 状态与列表存在 sessionStorage：跟着标签页会话走，切换路由 / 刷新都还在 */
const STORAGE_KEY = 'qxfly_image_host_upload_tasks'
/** 同时上传几张。并发太高会互相抢带宽，进度和 ETA 也不准 */
const CONCURRENCY = 2

/**
 * 上传会话。
 * <p>
 * 文件一加进来就进列表（立刻可见），上传在 store 里跑，
 * 所以**切换路由不会中断**，浮层在整个应用里都能看到进度。
 * 列表与状态写进 sessionStorage（绑定标签页会话），刷新页面也还在；
 * 但 File 句柄存不进 sessionStorage，所以刷新后正在传的任务会标记为「已中断」，需要重新添加文件。
 */
export const useUploadStore = defineStore('upload', () => {
  const tasks = ref<UploadTask[]>([])
  /** 文件只在内存里；上传成功或取消后释放 */
  const files = new Map<string, File>()
  const controllers = new Map<string, AbortController>()

  let running = 0
  let persistTimer: number | undefined
  let unloadBound = false

  const active = computed(() => tasks.value.filter((task) => task.status === 'pending' || task.status === 'uploading'))
  const activeCount = computed(() => active.value.length)
  const successTasks = computed(() => tasks.value.filter((task) => task.status === 'success'))
  const failedCount = computed(() => tasks.value.filter((task) => task.status === 'failed').length)

  /** 正在上传的总速度 */
  const totalSpeed = computed(() => active.value.reduce((sum, task) => sum + task.speed, 0))
  /** 进行中任务的总体进度 */
  const overallProgress = computed(() => {
    const list = active.value
    if (list.length === 0) {
      return 100
    }
    const total = list.reduce((sum, task) => sum + Math.max(task.size, 1), 0)
    const loaded = list.reduce((sum, task) => sum + task.loaded, 0)
    return Math.min(100, Math.round((loaded * 100) / total))
  })
  /** 剩余总时间（按当前总速度估算） */
  const overallEta = computed(() => {
    const list = active.value
    if (list.length === 0 || totalSpeed.value <= 0) {
      return -1
    }
    const rest = list.reduce((sum, task) => sum + Math.max(task.size - task.loaded, 0), 0)
    return rest / totalSpeed.value
  })

  restore()

  function restore() {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return
    }
    try {
      const list = JSON.parse(raw) as UploadTask[]
      tasks.value = list.map((task) => {
        if (task.status === 'pending' || task.status === 'uploading') {
          // 刷新后文件句柄没了，这几张传不了了
          return { ...task, status: 'failed' as UploadStatus, speed: 0, eta: -1, error: '页面刷新导致中断，请重新添加文件' }
        }
        return { ...task, speed: 0, eta: -1 }
      })
    } catch {
      sessionStorage.removeItem(STORAGE_KEY)
    }
  }

  function persistNow() {
    try {
      // 速度 / ETA 是瞬时值，没必要持久化
      const plain = tasks.value.map((task) => ({ ...task, speed: 0, eta: -1 }))
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(plain))
    } catch {
      // 存储满了就放弃持久化，不影响上传本身
    }
  }

  /** 进度回调很密集，落盘做 400ms 节流 */
  function schedulePersist() {
    if (persistTimer !== undefined) {
      return
    }
    persistTimer = window.setTimeout(() => {
      persistTimer = undefined
      persistNow()
    }, 400)
  }

  function onBeforeUnload(event: BeforeUnloadEvent) {
    event.preventDefault()
    event.returnValue = ''
  }

  watch(activeCount, (count) => {
    if (count > 0 && !unloadBound) {
      window.addEventListener('beforeunload', onBeforeUnload)
      unloadBound = true
    } else if (count === 0 && unloadBound) {
      window.removeEventListener('beforeunload', onBeforeUnload)
      unloadBound = false
    }
  })

  function newId() {
    return 'u' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
  }

  /** 添加文件：立刻进列表，然后按并发开始传 */
  function addFiles(list: File[], storageConfigId?: number) {
    if (list.length === 0) {
      return 0
    }
    const created: UploadTask[] = list.map((file) => {
      const id = newId()
      files.set(id, file)
      return {
        id,
        name: file.name || 'image',
        size: file.size,
        type: file.type || '',
        status: 'pending' as UploadStatus,
        loaded: 0,
        progress: 0,
        speed: 0,
        eta: -1,
        storageConfigId,
        createdAt: Date.now(),
      }
    })
    // 新加的排最前，添加后立刻能看到
    tasks.value = [...created, ...tasks.value]
    schedulePersist()
    void pump()
    return created.length
  }

  async function pump() {
    while (running < CONCURRENCY) {
      const next = tasks.value.find((task) => task.status === 'pending')
      if (!next) {
        return
      }
      void start(next)
    }
  }

  async function start(task: UploadTask) {
    const file = files.get(task.id)
    if (!file) {
      task.status = 'failed'
      task.error = '文件已不可用，请重新添加'
      schedulePersist()
      return
    }
    running += 1
    task.status = 'uploading'
    task.error = undefined
    task.finishedAt = undefined
    const controller = new AbortController()
    controllers.set(task.id, controller)
    const startedAt = Date.now()

    try {
      const result = await uploadImageWithProgress(
        file,
        task.storageConfigId,
        (loaded, total) => {
          const elapsed = (Date.now() - startedAt) / 1000
          task.loaded = loaded
          task.progress = total > 0 ? Math.min(100, Math.round((loaded * 100) / total)) : 0
          // 前 300ms 的瞬时速度波动很大，先不显示
          task.speed = elapsed > 0.3 ? loaded / elapsed : 0
          const rest = Math.max(total - loaded, 0)
          task.eta = task.speed > 0 ? rest / task.speed : -1
          schedulePersist()
        },
        controller.signal,
      )
      const image = result.data
      task.status = 'success'
      task.progress = 100
      task.loaded = task.size
      task.speed = 0
      task.eta = 0
      task.imageId = image.id
      task.url = image.url
      task.previewUrl = image.previewUrl
      task.copyUrl = image.copyUrl
      task.markdown = image.markdown
      task.storageTypeLabel = image.storageTypeLabel
      task.finishedAt = Date.now()
      files.delete(task.id)
    } catch (error) {
      const aborted = controller.signal.aborted
      task.status = aborted ? 'canceled' : 'failed'
      task.speed = 0
      task.eta = -1
      task.finishedAt = Date.now()
      if (!aborted) {
        task.error = errorMessage(error)
      }
    } finally {
      running -= 1
      controllers.delete(task.id)
      schedulePersist()
      void pump()
    }
  }

  function errorMessage(error: unknown): string {
    const anyError = error as { message?: string; response?: { status?: number } }
    const status = anyError?.response?.status
    if (status === 413) {
      return '文件超过大小限制'
    }
    if (status === 401) {
      return '登录状态已失效'
    }
    return anyError?.message || '上传失败'
  }

  function cancel(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task) {
      return
    }
    if (task.status === 'pending') {
      task.status = 'canceled'
      task.finishedAt = Date.now()
      files.delete(id)
      schedulePersist()
      // 取消排队中的任务后，腾出的并发位要让给后面的
      void pump()
      return
    }
    controllers.get(id)?.abort()
  }

  function cancelAll() {
    for (const task of active.value) {
      cancel(task.id)
    }
  }

  /** 失败 / 取消的可以重试（前提是文件还在内存里） */
  function retry(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task || !files.has(id)) {
      return false
    }
    task.status = 'pending'
    task.loaded = 0
    task.progress = 0
    task.speed = 0
    task.eta = -1
    task.error = undefined
    schedulePersist()
    void pump()
    return true
  }

  function canRetry(id: string) {
    return files.has(id)
  }

  function remove(id: string) {
    cancel(id)
    tasks.value = tasks.value.filter((task) => task.id !== id)
    files.delete(id)
    schedulePersist()
  }

  function clearFinished() {
    tasks.value = tasks.value.filter((task) => task.status === 'pending' || task.status === 'uploading')
    schedulePersist()
  }

  function clearAll() {
    cancelAll()
    tasks.value = []
    files.clear()
    schedulePersist()
  }

  /** 仅供参考：页面卸载前把没落盘的进度写下去 */
  function flush() {
    persistNow()
  }

  return {
    tasks,
    active,
    activeCount,
    successTasks,
    failedCount,
    totalSpeed,
    overallProgress,
    overallEta,
    addFiles,
    cancel,
    cancelAll,
    retry,
    canRetry,
    remove,
    clearFinished,
    clearAll,
    flush,
  }
})
