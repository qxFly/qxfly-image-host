/**
 * 图库缩略图缓存（IndexedDB）。
 *
 * 目标：进入图库时不要每次都重新下载图片。
 * 策略：
 *   1. 命中缓存且距上次校验不超过 REVALIDATE_AFTER -> 直接用缓存，零网络请求
 *   2. 超过校验窗口 -> 带 If-None-Match / If-Modified-Since 做条件请求
 *        304 -> 内容没变，只更新校验时间（省掉整个响应体）
 *        200 且 ETag 变了 -> 替换缓存
 *   3. 请求失败（例如对方 CDN 没开 CORS）-> 回退到直接用原图地址，行为与改造前一致
 *
 * 对于同一台主机，只要失败过一次就记住并跳过缓存，避免每张图都白发一次请求。
 */

const DB_NAME = 'qxfly-image-host-cache'
const DB_VERSION = 1
const STORE = 'images'

/** 校验窗口：这段时间内直接用缓存，不发任何请求 */
const REVALIDATE_AFTER = 30 * 60 * 1000
/** 缓存条目上限，超出后按最久未校验淘汰 */
const MAX_ENTRIES = 3000

export interface CacheEntry {
  url: string
  blob: Blob
  etag?: string
  lastModified?: string
  size: number
  storedAt: number
  checkedAt: number
}

export interface CacheStats {
  entries: number
  bytes: number
  supported: boolean
}

let dbPromise: Promise<IDBDatabase> | null = null
/** 记录 fetch 拿不到内容的主机（CORS / 跨域被拒），这些主机直接走原图 */
const unsupportedHosts = new Set<string>()
const inflight = new Map<string, Promise<string>>()
let writesSincePrune = 0

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) {
    return dbPromise
  }
  dbPromise = new Promise<IDBDatabase>((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('当前环境不支持 IndexedDB'))
      return
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'url' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB 打开失败'))
  }).catch((error) => {
    dbPromise = null
    throw error
  })
  return dbPromise
}

function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(STORE, mode)
        const request = run(transaction.objectStore(STORE))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      }),
  )
}

async function getEntry(url: string): Promise<CacheEntry | undefined> {
  try {
    return (await tx<CacheEntry | undefined>('readonly', (store) => store.get(url) as IDBRequest<CacheEntry | undefined>)) || undefined
  } catch {
    return undefined
  }
}

async function putEntry(entry: CacheEntry): Promise<void> {
  try {
    await tx('readwrite', (store) => store.put(entry) as IDBRequest<IDBValidKey>)
    writesSincePrune += 1
    if (writesSincePrune >= 200) {
      writesSincePrune = 0
      void prune()
    }
  } catch {
    // 缓存写失败不影响显示
  }
}

/** 超出上限时按 checkedAt 淘汰最旧的一批 */
async function prune(): Promise<void> {
  try {
    const all = await tx<CacheEntry[]>('readonly', (store) => store.getAll() as IDBRequest<CacheEntry[]>)
    if (all.length <= MAX_ENTRIES) {
      return
    }
    all.sort((a, b) => a.checkedAt - b.checkedAt)
    const remove = all.slice(0, all.length - MAX_ENTRIES)
    const db = await openDb()
    const transaction = db.transaction(STORE, 'readwrite')
    const store = transaction.objectStore(STORE)
    remove.forEach((entry) => store.delete(entry.url))
  } catch {
    // 忽略
  }
}

export async function cacheStats(): Promise<CacheStats> {
  try {
    const all = await tx<CacheEntry[]>('readonly', (store) => store.getAll() as IDBRequest<CacheEntry[]>)
    return {
      entries: all.length,
      bytes: all.reduce((sum, entry) => sum + (entry.size || 0), 0),
      supported: true,
    }
  } catch {
    return { entries: 0, bytes: 0, supported: false }
  }
}

export async function clearImageCache(): Promise<void> {
  try {
    await tx('readwrite', (store) => store.clear() as IDBRequest<undefined>)
  } catch {
    // 忽略
  }
}

function hostOf(url: string): string {
  try {
    return new URL(url, window.location.href).host
  } catch {
    return url
  }
}

/**
 * 解析出可直接给 <img> 使用的地址。
 * 正常情况返回 blob: 地址（来自缓存），失败时返回原始 URL。
 */
export function resolveImage(url: string): Promise<string> {
  if (!url) {
    return Promise.resolve(url)
  }
  const host = hostOf(url)
  if (unsupportedHosts.has(host)) {
    return Promise.resolve(url)
  }
  const existing = inflight.get(url)
  if (existing) {
    return existing
  }
  const task = doResolve(url, host).finally(() => inflight.delete(url))
  inflight.set(url, task)
  return task
}

/** 生成可直接给 img 用的地址；生成失败就退回原图地址（不改动主机策略） */
function toDisplayUrl(blob: Blob, fallback: string): string {
  try {
    return URL.createObjectURL(blob)
  } catch {
    return fallback
  }
}

async function doResolve(url: string, host: string): Promise<string> {
  const entry = await getEntry(url)
  const now = Date.now()

  // 1. 窗口内直接命中，不发任何请求
  if (entry && now - entry.checkedAt < REVALIDATE_AFTER) {
    return toDisplayUrl(entry.blob, url)
  }

  // 2. 条件请求：把缓存里的校验信息带上，服务端内容没变就回 304
  let response: Response
  try {
    const headers: Record<string, string> = {}
    if (entry?.etag) {
      headers['If-None-Match'] = entry.etag
    }
    if (entry?.lastModified) {
      headers['If-Modified-Since'] = entry.lastModified
    }
    response = await fetch(url, { headers, cache: 'no-store' })
  } catch {
    // 只有"连内容都拿不到"（多为对方 CDN 未开 CORS）才放弃这台主机的缓存，
    // 避免每张图都白发一次注定失败的请求
    if (entry) {
      await putEntry({ ...entry, checkedAt: now })
      return toDisplayUrl(entry.blob, url)
    }
    unsupportedHosts.add(host)
    return url
  }

  try {
    if (response.status === 304 && entry) {
      // 内容没变：只更新时间戳，不重新下载
      await putEntry({ ...entry, checkedAt: now })
      return toDisplayUrl(entry.blob, url)
    }

    if (!response.ok) {
      // 服务端明确报错（404/500…）说明网络是通的，不能因此禁用整台主机的缓存。
      // 有旧缓存就继续用旧的，否则回退原图。
      return entry ? toDisplayUrl(entry.blob, url) : url
    }

    const etag = response.headers.get('ETag') || undefined
    const lastModified = response.headers.get('Last-Modified') || undefined
    const sameAsCached = !!entry && !!etag && etag === entry.etag

    if (sameAsCached) {
      // ETag 一致 = 内容一致，直接复用缓存内容，连响应体都不用读
      await putEntry({ ...entry, checkedAt: now, lastModified: lastModified || entry.lastModified })
      return toDisplayUrl(entry.blob, url)
    }

    const blob = await response.blob()
    await putEntry({ url, blob, etag, lastModified, size: blob.size, storedAt: now, checkedAt: now })
    return toDisplayUrl(blob, url)
  } catch {
    return entry ? toDisplayUrl(entry.blob, url) : url
  }
}
