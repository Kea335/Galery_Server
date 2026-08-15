import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { pipeline } from 'node:stream/promises'
import { config } from './config.js'

/**
 * Content-addressed layout (§7):
 *   blobs/ab/cd/abcdef0123...
 * The hash is the coat-check ticket: identical files share one hook on disk.
 */
export function blobPath(sha256) {
  return path.join(config.blobsDir, sha256.slice(0, 2), sha256.slice(2, 4), sha256)
}

export function thumbPath(sha256, size) {
  return path.join(
    config.thumbsDir,
    sha256.slice(0, 2),
    sha256.slice(2, 4),
    `${sha256}_${size}.webp`,
  )
}

export function trashPath(sha256) {
  return path.join(config.trashDir, sha256.slice(0, 2), sha256.slice(2, 4), sha256)
}

export function partPath(uploadId) {
  return path.join(config.tmpDir, `${uploadId}.part`)
}

export async function ensureDirs() {
  for (const dir of [config.blobsDir, config.thumbsDir, config.trashDir, config.tmpDir]) {
    await fsp.mkdir(dir, { recursive: true })
  }
}

export async function hashFile(filePath) {
  const hash = crypto.createHash('sha256')
  // 64 KB buffers, same as the Android side (§10.2) — bounded memory on a 4 GB box.
  await pipeline(fs.createReadStream(filePath, { highWaterMark: 64 * 1024 }), hash)
  return hash.digest('hex')
}

export async function fileSize(filePath) {
  try {
    const st = await fsp.stat(filePath)
    return st.size
  } catch (err) {
    if (err.code === 'ENOENT') return null
    throw err
  }
}

export async function exists(filePath) {
  try {
    await fsp.access(filePath)
    return true
  } catch {
    return false
  }
}

/**
 * Move a finished part file into its content-addressed home.
 * Falls back to copy+unlink when tmp and blobs sit on different filesystems.
 */
export async function moveIntoPlace(from, to) {
  await fsp.mkdir(path.dirname(to), { recursive: true })
  try {
    await fsp.rename(from, to)
  } catch (err) {
    if (err.code !== 'EXDEV') throw err
    await fsp.copyFile(from, to)
    await fsp.unlink(from)
  }
}

/**
 * Free bytes on the media volume, or null when the filesystem will not say.
 * A null is treated as "go ahead" — refusing every upload because statfs is
 * unsupported would be worse than the risk it guards against.
 */
export async function freeBytes() {
  try {
    const stats = await fsp.statfs(config.dataDir)
    return Number(stats.bavail) * Number(stats.bsize)
  } catch {
    return null
  }
}

export const SHA256_RE = /^[0-9a-f]{64}$/
