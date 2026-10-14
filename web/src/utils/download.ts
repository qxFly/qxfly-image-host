/**
 * 把后端返回的二进制内容存成文件。
 *
 * 下载接口要带 JWT，所以不能用 <a href> 直接指过去：这里先拿 Blob，
 * 再造一个临时地址点一下，用完再回收。
 */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 立刻 revoke 会让部分浏览器来不及取内容，延后释放
  window.setTimeout(() => URL.revokeObjectURL(url), 60000)
}

/** 直接按 URL 取回二进制内容；跨域被拒或网络失败时抛错，由调用方兜底 */
export async function fetchBlobUrl(url: string): Promise<Blob> {
  const response = await fetch(url, { credentials: 'omit' })
  if (!response.ok) {
    throw new Error('HTTP ' + response.status)
  }
  return await response.blob()
}

/** 生成带时间戳的压缩包名，例如 qxfly-images-20261013-143025.zip */
export function zipFileName(prefix = 'qxfly-images'): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp =
    String(now.getFullYear()) +
    pad(now.getMonth() + 1) +
    pad(now.getDate()) +
    '-' +
    pad(now.getHours()) +
    pad(now.getMinutes()) +
    pad(now.getSeconds())
  return prefix + '-' + stamp + '.zip'
}
