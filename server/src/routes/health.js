import fsp from 'node:fs/promises'
import { config } from '../config.js'
import { ok } from '../errors.js'
import { hasFfmpeg } from '../thumbs.js'

const startedAt = Date.now()

/**
 * Ops endpoint (§9). Deliberately unauthenticated so systemd, a uptime probe,
 * or the app's onboarding screen can confirm reachability before a token
 * exists. It exposes no media and no identifiers.
 */
export default async function healthRoutes(app, { db }) {
  app.get('/health', async (_request, reply) => {
    let freeDiskBytes = null
    try {
      const st = await fsp.statfs(config.dataDir)
      freeDiskBytes = Number(st.bavail) * Number(st.bsize)
    } catch {
      // statfs is unavailable on some filesystems; report null rather than fail.
    }

    const assetCount = db
      .prepare('SELECT COUNT(*) AS c FROM assets WHERE deleted_at IS NULL')
      .get().c

    const trashedCount = db
      .prepare('SELECT COUNT(*) AS c FROM assets WHERE deleted_at IS NOT NULL')
      .get().c

    const pendingUploads = db.prepare('SELECT COUNT(*) AS c FROM upload_sessions').get().c

    return ok(reply, {
      version: config.version,
      uptimeSec: Math.floor((Date.now() - startedAt) / 1000),
      freeDiskBytes,
      assetCount,
      trashedCount,
      pendingUploads,
      dbSizeBytes: await dbSize(),
      rssBytes: process.memoryUsage.rss(),
      thumbnails: (await hasFfmpeg()) ? 'available' : 'ffmpeg-missing',
    })
  })
}

async function dbSize() {
  let total = 0
  for (const suffix of ['', '-wal', '-shm']) {
    try {
      total += (await fsp.stat(config.dbPath + suffix)).size
    } catch {
      // missing sidecar files are normal
    }
  }
  return total
}
