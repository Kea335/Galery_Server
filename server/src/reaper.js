import fsp from 'node:fs/promises'
import path from 'node:path'
import { config } from './config.js'
import { partPath, trashPath } from './storage.js'

/**
 * Housekeeping: expired upload sessions, orphaned partials, and trash past its
 * 30-day retention (§7, §9). Runs hourly and does nothing expensive.
 */
export async function reap(db, log) {
  const now = Date.now()
  const stats = { sessions: 0, orphans: 0, purged: 0 }

  const expired = db.prepare('SELECT id FROM upload_sessions WHERE expires_at <= ?').all(now)
  for (const row of expired) {
    await unlinkQuiet(partPath(row.id))
    db.prepare('DELETE FROM upload_sessions WHERE id = ?').run(row.id)
    stats.sessions += 1
  }

  // A .part with no session row is dead weight — usually a crash mid-create.
  try {
    const live = new Set(db.prepare('SELECT id FROM upload_sessions').all().map((r) => r.id))
    for (const name of await fsp.readdir(config.tmpDir)) {
      if (!name.endsWith('.part')) continue
      if (live.has(name.slice(0, -'.part'.length))) continue
      await unlinkQuiet(path.join(config.tmpDir, name))
      stats.orphans += 1
    }
  } catch (err) {
    if (err.code !== 'ENOENT') throw err
  }

  const cutoff = now - config.trashRetentionMs
  const stale = db
    .prepare('SELECT sha256 FROM assets WHERE deleted_at IS NOT NULL AND deleted_at <= ?')
    .all(cutoff)
  for (const row of stale) {
    if (await unlinkQuiet(trashPath(row.sha256))) stats.purged += 1
  }

  if (stats.sessions || stats.orphans || stats.purged) {
    log?.info(stats, 'reaper pass complete')
  }
  return stats
}

async function unlinkQuiet(filePath) {
  try {
    await fsp.unlink(filePath)
    return true
  } catch (err) {
    if (err.code === 'ENOENT') return false
    throw err
  }
}
