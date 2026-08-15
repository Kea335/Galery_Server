import fs from 'node:fs'
import fsp from 'node:fs/promises'
import { config } from '../config.js'
import { nextUpdatedAt } from '../db.js'
import { fail, ok } from '../errors.js'
import { blobPath, exists, moveIntoPlace, trashPath } from '../storage.js'
import { THUMB_SIZES, ensureThumb, hasFfmpeg } from '../thumbs.js'

export default async function assetRoutes(app, { db, requireDevice }) {
  app.addHook('preHandler', requireDevice)

  /**
   * Delta sync (§9). Ordered by updated_at so the client can page forward with
   * a single cursor; deleted rows come through as tombstones so the phone can
   * drop them from its own index.
   */
  app.get('/assets', async (request, reply) => {
    const since = Number(request.query.since ?? 0)
    if (!Number.isFinite(since) || since < 0) {
      return fail(reply, 400, 'BAD_REQUEST', 'since must be a non-negative epoch-ms value.')
    }

    const requested = Number(request.query.limit ?? config.maxListLimit)
    const limit = Math.min(
      Number.isFinite(requested) && requested > 0 ? Math.trunc(requested) : config.maxListLimit,
      config.maxListLimit,
    )

    const rows = db
      .prepare('SELECT * FROM assets WHERE updated_at > ? ORDER BY updated_at ASC LIMIT ?')
      .all(since, limit)

    const cursor = rows.length ? rows[rows.length - 1].updated_at : since
    return ok(reply, {
      assets: rows.map(serializeAsset),
      nextCursor: cursor,
      hasMore: rows.length === limit,
    })
  })

  /**
   * What is in the trash, with enough detail to show it.
   *
   * §9 defines delete and restore but not a way to list; delta sync only
   * carries tombstones, which are id and timestamp. The trash screen needs
   * names, sizes and dates, so this fills the gap.
   */
  app.get('/assets/trash', async (request, reply) => {
    const requested = Number(request.query.limit ?? config.maxListLimit)
    const limit = Math.min(
      Number.isFinite(requested) && requested > 0 ? Math.trunc(requested) : config.maxListLimit,
      config.maxListLimit,
    )

    const rows = db
      .prepare(
        'SELECT * FROM assets WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT ?',
      )
      .all(limit)

    const now = Date.now()
    return ok(reply, {
      assets: rows.map((row) => ({
        ...serializeLiveFields(row),
        deleted: true,
        deletedAt: row.deleted_at,
        // The reaper purges at 30 days; tell the client how long is left so it
        // can say so out loud rather than deleting silently.
        purgesInMs: Math.max(0, row.deleted_at + config.trashRetentionMs - now),
      })),
      retentionDays: Math.round(config.trashRetentionMs / 86_400_000),
    })
  })

  app.get('/assets/:id', async (request, reply) => {
    const asset = db.prepare('SELECT * FROM assets WHERE id = ?').get(request.params.id)
    if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset.')
    return ok(reply, serializeAsset(asset))
  })

  /**
   * Original bytes with Range support — this is what makes video seeking work
   * (§9, §11). No transcoding, no re-encoding: the phone's decoder does it.
   */
  app.get('/assets/:id/file', async (request, reply) => {
    const asset = db
      .prepare('SELECT * FROM assets WHERE id = ? AND deleted_at IS NULL')
      .get(request.params.id)
    if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset.')

    const filePath = blobPath(asset.sha256)
    let stat
    try {
      stat = await fsp.stat(filePath)
    } catch {
      return fail(reply, 410, 'BLOB_MISSING', 'The stored file is gone from disk.')
    }

    const etag = `"${asset.sha256}"`
    reply.header('Accept-Ranges', 'bytes')
    reply.header('ETag', etag)
    reply.header('Cache-Control', 'private, max-age=31536000, immutable')
    reply.header('Last-Modified', new Date(asset.uploaded_at).toUTCString())
    // Content-Type is set only on the paths that actually send bytes — setting
    // it up front makes Fastify refuse to serialize a JSON error body (416).

    if (request.headers['if-none-match'] === etag) {
      return reply.code(304).send()
    }

    const rangeHeader = request.headers.range
    // If-Range guards against a mid-download file swap; with content addressing
    // the ETag can never change for a given id, but honour it anyway.
    const ifRange = request.headers['if-range']
    const rangeAllowed = !ifRange || ifRange === etag

    if (rangeHeader && rangeAllowed) {
      const range = parseRange(rangeHeader, stat.size)
      if (range === 'unsatisfiable') {
        reply.header('Content-Range', `bytes */${stat.size}`)
        return fail(reply, 416, 'RANGE_NOT_SATISFIABLE', 'Requested range is outside the file.')
      }
      if (range) {
        reply.code(206)
        reply.header('Content-Type', asset.mime_type)
        reply.header('Content-Range', `bytes ${range.start}-${range.end}/${stat.size}`)
        reply.header('Content-Length', range.end - range.start + 1)
        return reply.send(fs.createReadStream(filePath, { start: range.start, end: range.end }))
      }
    }

    reply.header('Content-Type', asset.mime_type)
    reply.header('Content-Length', stat.size)
    return reply.send(fs.createReadStream(filePath))
  })

  app.get('/assets/:id/thumb', async (request, reply) => {
    const asset = db
      .prepare('SELECT * FROM assets WHERE id = ? AND deleted_at IS NULL')
      .get(request.params.id)
    if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset.')

    const size = Number(request.query.size ?? 512)
    if (!THUMB_SIZES.has(size)) {
      return fail(reply, 400, 'BAD_SIZE', 'size must be 256 or 512.')
    }

    const cached = await ensureThumb(asset, size, { log: request.log })
    if (!cached) {
      const reason = (await hasFfmpeg())
        ? 'Thumbnail could not be generated for this file.'
        : 'ffmpeg is not installed on the server.'
      return fail(reply, 503, 'THUMB_UNAVAILABLE', reason)
    }

    const etag = `"${asset.sha256}_${size}"`
    if (request.headers['if-none-match'] === etag) return reply.code(304).send()

    const stat = await fsp.stat(cached)
    reply.header('ETag', etag)
    reply.header('Content-Type', 'image/webp')
    reply.header('Content-Length', stat.size)
    reply.header('Cache-Control', 'private, max-age=31536000, immutable')
    return reply.send(fs.createReadStream(cached))
  })

  /**
   * Soft delete: the row is tombstoned and the blob is parked in trash/ for 30
   * days. Nothing is destroyed on the spot (§2, §7).
   */
  app.delete('/assets/:id', async (request, reply) => {
    const asset = db.prepare('SELECT * FROM assets WHERE id = ?').get(request.params.id)
    if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset.')
    if (asset.deleted_at !== null) return ok(reply, { id: asset.id, deleted: true })

    const from = blobPath(asset.sha256)
    if (await exists(from)) {
      await moveIntoPlace(from, trashPath(asset.sha256))
    }

    const now = nextUpdatedAt(db)
    db.prepare('UPDATE assets SET deleted_at = ?, updated_at = ? WHERE id = ?').run(
      now,
      now,
      asset.id,
    )
    return ok(reply, { id: asset.id, deleted: true, deletedAt: now })
  })

  app.post('/assets/:id/restore', async (request, reply) => {
    const asset = db.prepare('SELECT * FROM assets WHERE id = ?').get(request.params.id)
    if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset.')
    if (asset.deleted_at === null) return ok(reply, serializeAsset(asset))

    const dest = blobPath(asset.sha256)
    const from = trashPath(asset.sha256)
    if (!(await exists(dest))) {
      if (!(await exists(from))) {
        return fail(reply, 410, 'BLOB_MISSING', 'The file has already been purged from trash.')
      }
      await moveIntoPlace(from, dest)
    }

    const now = nextUpdatedAt(db)
    db.prepare('UPDATE assets SET deleted_at = NULL, updated_at = ? WHERE id = ?').run(now, asset.id)
    return ok(reply, serializeAsset(db.prepare('SELECT * FROM assets WHERE id = ?').get(asset.id)))
  })
}

function serializeAsset(row) {
  if (row.deleted_at !== null) {
    return { id: row.id, deleted: true, deletedAt: row.deleted_at, updatedAt: row.updated_at }
  }
  return { ...serializeLiveFields(row), deleted: false }
}

function serializeLiveFields(row) {
  return {
    id: row.id,
    sha256: row.sha256,
    sizeBytes: row.size_bytes,
    mimeType: row.mime_type,
    filename: row.filename,
    capturedAt: row.captured_at,
    uploadedAt: row.uploaded_at,
    width: row.width,
    height: row.height,
    durationMs: row.duration_ms,
    orientation: row.orientation,
    lat: row.lat,
    lon: row.lon,
    deviceId: row.device_id,
    updatedAt: row.updated_at,
  }
}

/**
 * Single-range parser. Multi-range requests are answered with the whole file,
 * which is legal and simpler than assembling a multipart/byteranges body.
 */
export function parseRange(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim())
  if (!match) return null

  const [, rawStart, rawEnd] = match
  if (rawStart === '' && rawEnd === '') return null

  let start
  let end

  if (rawStart === '') {
    const suffix = Number(rawEnd)
    if (suffix === 0) return 'unsatisfiable'
    start = Math.max(0, size - suffix)
    end = size - 1
  } else {
    start = Number(rawStart)
    end = rawEnd === '' ? size - 1 : Math.min(Number(rawEnd), size - 1)
  }

  if (!Number.isFinite(start) || !Number.isFinite(end)) return null
  if (start >= size || start > end) return 'unsatisfiable'
  return { start, end }
}
