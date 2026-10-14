/**
 * 测量「本机到某个 URL」的往返耗时。
 *
 * 用 no-cors 的 HEAD 请求：即使对方没开 CORS 也能发出去，
 * 拿到的是 opaque 响应 —— 我们只关心「多久有回应 / 有没有回应」，不读内容，正好够用。
 * DNS 失败、断网、超时会抛错，判定为不可达。
 */
async function once(url: string, timeoutMs: number): Promise<number | null> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  const start = performance.now()
  try {
    await fetch(url, {
      method: 'HEAD',
      mode: 'no-cors',
      cache: 'no-store',
      redirect: 'follow',
      signal: controller.signal,
    })
    return Math.round(performance.now() - start)
  } catch {
    return null
  } finally {
    window.clearTimeout(timer)
  }
}

/**
 * 多次测量取最快的一次（首次往往包含 DNS / TLS 握手，偏慢）。
 *
 * @returns 毫秒数；null 表示不可达
 */
export async function measureLatency(url: string, attempts = 2, timeoutMs = 8000): Promise<number | null> {
  if (!url) {
    return null
  }
  let best: number | null = null
  for (let i = 0; i < Math.max(1, attempts); i++) {
    const ms = await once(url, timeoutMs)
    if (ms === null) {
      continue
    }
    best = best === null ? ms : Math.min(best, ms)
  }
  return best
}

/** 延时对应的标签颜色 */
export function latencyTagType(ms: number | null | undefined): 'success' | 'warning' | 'danger' | 'info' {
  if (ms === null) {
    return 'danger'
  }
  if (ms === undefined) {
    return 'info'
  }
  if (ms < 200) {
    return 'success'
  }
  return ms < 600 ? 'warning' : 'danger'
}
