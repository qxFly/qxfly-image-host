// 生成 Tauri 所需的图标文件（无第三方依赖）。
// 用法：node scripts/generate-icons.mjs
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '..', 'web', 'src-tauri', 'icons')
mkdirSync(outDir, { recursive: true })

const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n += 1) {
    let c = n
    for (let k = 0; k < 8; k += 1) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    }
    table[n] = c
  }
  return table
})()

function crc32(buffer) {
  let crc = -1
  for (let i = 0; i < buffer.length; i += 1) {
    crc = CRC_TABLE[(crc ^ buffer[i]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ -1) >>> 0
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length, 0)
  const typeBuffer = Buffer.from(type, 'ascii')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0)
  return Buffer.concat([length, typeBuffer, data, crc])
}

function encodePng(width, height, rgba) {
  const stride = width * 4
  const raw = Buffer.alloc((stride + 1) * height)
  for (let y = 0; y < height; y += 1) {
    raw[y * (stride + 1)] = 0
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  const header = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  return Buffer.concat([
    header,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/** 绘制一枚「图片」主题的应用图标 */
function drawIcon(size) {
  const rgba = Buffer.alloc(size * size * 4)
  const cx = size / 2
  const cy = size / 2
  const radius = size * 0.22
  const half = size / 2
  const inRoundedRect = (x, y) => {
    const qx = Math.abs(x - cx) - (half - radius)
    const qy = Math.abs(y - cy) - (half - radius)
    const outside = Math.sqrt(Math.max(qx, 0) ** 2 + Math.max(qy, 0) ** 2)
    const inside = Math.min(Math.max(qx, qy), 0)
    return outside + inside - radius <= 0
  }
  const sunX = size * 0.68
  const sunY = size * 0.31
  const sunR = size * 0.085

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const offset = (y * size + x) * 4
      if (!inRoundedRect(x + 0.5, y + 0.5)) {
        continue
      }
      const t = y / size
      let r = Math.round(43 + t * (78 - 43))
      let g = Math.round(88 + t * (67 - 88))
      let b = Math.round(118 + t * (118 - 118))

      const sunDist = Math.sqrt((x - sunX) ** 2 + (y - sunY) ** 2)
      if (sunDist <= sunR) {
        r = 255
        g = 236
        b = 179
      }

      const baseY = size * 0.80
      const apexY = size * 0.38
      if (y >= apexY && y <= baseY) {
        const ratio = (y - apexY) / (baseY - apexY)
        const half = ratio * size * 0.36
        if (Math.abs(x - cx) <= half) {
          r = 255
          g = 255
          b = 255
        }
      }

      const secondBaseY = size * 0.80
      const secondApexY = size * 0.55
      const secondCx = size * 0.30
      if (y >= secondApexY && y <= secondBaseY) {
        const ratio = (y - secondApexY) / (secondBaseY - secondApexY)
        const half = ratio * size * 0.24
        if (Math.abs(x - secondCx) <= half) {
          r = 214
          g = 232
          b = 255
        }
      }

      rgba[offset] = r
      rgba[offset + 1] = g
      rgba[offset + 2] = b
      rgba[offset + 3] = 255
    }
  }
  return rgba
}

function resize(source, sourceSize, targetSize) {
  const rgba = Buffer.alloc(targetSize * targetSize * 4)
  for (let y = 0; y < targetSize; y += 1) {
    for (let x = 0; x < targetSize; x += 1) {
      const sx = Math.min(sourceSize - 1, Math.floor((x * sourceSize) / targetSize))
      const sy = Math.min(sourceSize - 1, Math.floor((y * sourceSize) / targetSize))
      source.copy(rgba, (y * targetSize + x) * 4, (sy * sourceSize + sx) * 4, (sy * sourceSize + sx) * 4 + 4)
    }
  }
  return rgba
}

/** 打包成 ICO（内嵌 PNG，Windows Vista 及以上支持） */
function encodeIco(pngBuffer, size) {
  const header = Buffer.alloc(6)
  header.writeUInt16LE(0, 0)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(1, 4)

  const entry = Buffer.alloc(16)
  entry[0] = size >= 256 ? 0 : size
  entry[1] = size >= 256 ? 0 : size
  entry[2] = 0
  entry[3] = 0
  entry.writeUInt16LE(1, 4)
  entry.writeUInt16LE(32, 6)
  entry.writeUInt32LE(pngBuffer.length, 8)
  entry.writeUInt32LE(22, 12)

  return Buffer.concat([header, entry, pngBuffer])
}

const master = 512
const masterPixels = drawIcon(master)

const outputs = [
  ['icon.png', 512],
  ['128x128@2x.png', 256],
  ['128x128.png', 128],
  ['32x32.png', 32],
]

for (const [name, size] of outputs) {
  const pixels = size === master ? masterPixels : resize(masterPixels, master, size)
  const png = encodePng(size, size, pixels)
  writeFileSync(resolve(outDir, name), png)
  console.log('wrote icons/' + name + ' (' + png.length + ' bytes)')
}

const icoPixels = resize(masterPixels, master, 256)
const icoPng = encodePng(256, 256, icoPixels)
const ico = encodeIco(icoPng, 256)
writeFileSync(resolve(outDir, 'icon.ico'), ico)
console.log('wrote icons/icon.ico (' + ico.length + ' bytes)')
