import { spawn } from 'node:child_process'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { blobPath, exists, thumbPath } from './storage.js'

export const THUMB_SIZES = new Set([256, 512])

let ffmpegChecked = null

export async function hasFfmpeg() {
  if (ffmpegChecked !== null) return ffmpegChecked
  ffmpegChecked = await new Promise((resolve) => {
    const proc = spawn('ffmpeg', ['-version'], { stdio: 'ignore' })
    proc.on('error', () => resolve(false))
    proc.on('close', (code) => resolve(code === 0))
  })
  return ffmpegChecked
}

/**
 * One worker, lowest priority (§4). The box has two slow cores and an HDD —
 * running several ffmpeg processes at once would starve the API.
 */
let queue = Promise.resolve()

function enqueue(task) {
  const result = queue.then(task, task)
  queue = result.catch(() => {})
  return result
}

/**
 * Generate a thumbnail once, cache it forever. Returns the cached path, or
 * null if ffmpeg is unavailable.
 */
export async function ensureThumb(asset, size, { dryRun = false, log } = {}) {
  const out = thumbPath(asset.sha256, size)
  if (await exists(out)) return out
  if (!(await hasFfmpeg())) return null

  const source = blobPath(asset.sha256)
  if (!(await exists(source))) return null

  const isVideo = asset.mime_type.startsWith('video/')
  const args = []

  // Videos: grab a poster frame a second in, which skips black leaders.
  if (isVideo) args.push('-ss', '00:00:01')

  args.push(
    '-y',
    '-loglevel',
    'error',
    '-i',
    source,
    '-frames:v',
    '1',
    '-vf',
    `scale='min(${size},iw)':-2`,
    '-c:v',
    'libwebp',
    '-quality',
    '78',
    out,
  )

  if (dryRun) {
    log?.info({ sha256: asset.sha256, size, args }, 'thumb dry-run')
    return null
  }

  return enqueue(async () => {
    if (await exists(out)) return out
    await fsp.mkdir(path.dirname(out), { recursive: true })
    const okRun = await runFfmpeg(args, isVideo)
    if (!okRun) {
      log?.warn({ sha256: asset.sha256, size }, 'thumbnail generation failed')
      return null
    }
    return (await exists(out)) ? out : null
  })
}

function runFfmpeg(args, retryFromStart) {
  return new Promise((resolve) => {
    // nice only exists on POSIX; on Windows we just run ffmpeg directly.
    const [cmd, fullArgs] =
      process.platform === 'win32' ? ['ffmpeg', args] : ['nice', ['-n', '19', 'ffmpeg', ...args]]

    const proc = spawn(cmd, fullArgs, { stdio: 'ignore' })
    proc.on('error', () => resolve(false))
    proc.on('close', async (code) => {
      if (code === 0) return resolve(true)
      // A clip shorter than the 1 s seek point yields no frame — retry at 0.
      if (retryFromStart && args[0] === '-ss') {
        const fallback = args.slice(2)
        return resolve(await runFfmpeg(fallback, false))
      }
      resolve(false)
    })
  })
}
