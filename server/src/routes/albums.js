import crypto from 'node:crypto'
import { config } from '../config.js'
import { nextUpdatedAt } from '../db.js'
import { fail, ok } from '../errors.js'

const MAX_NAME_LENGTH = 200

/**
 * Albums (§16.6): manual, server-side, shared by every phone that signs in.
 *
 * There is deliberately no "album contents" endpoint. Clients already mirror
 * the asset library, and `GET /album-items` gives them membership; the contents
 * are a join they can do locally. The trash screen needed an endpoint of its
 * own because delta sync only carries tombstones there — here it carries the
 * whole relationship, so nothing is missing.
 */
export default async function albumRoutes(app, { db, requireDevice }) {
  app.addHook('preHandler', requireDevice)

  // ─── Delta sync (§9) ──────────────────────────────────────────────────────

  app.get('/albums', async (request, reply) => {
    const page = readPage(request, reply)
    if (!page) return

    const rows = db
      .prepare('SELECT * FROM albums WHERE updated_at > ? ORDER BY updated_at ASC LIMIT ?')
      .all(page.since, page.limit)

    return ok(reply, {
      albums: rows.map(serializeAlbum),
      nextCursor: rows.length ? rows[rows.length - 1].updated_at : page.since,
      hasMore: rows.length === page.limit,
    })
  })

  /**
   * Membership changes, additions and removals alike. A separate stream from
   * /albums because the two move at completely different rates — renaming an
   * album should not drag five thousand membership rows across the wire.
   */
  app.get('/album-items', async (request, reply) => {
    const page = readPage(request, reply)
    if (!page) return

    const rows = db
      .prepare('SELECT * FROM album_items WHERE updated_at > ? ORDER BY updated_at ASC LIMIT ?')
      .all(page.since, page.limit)

    return ok(reply, {
      items: rows.map(serializeItem),
      nextCursor: rows.length ? rows[rows.length - 1].updated_at : page.since,
      hasMore: rows.length === page.limit,
    })
  })

  // ─── Albums ───────────────────────────────────────────────────────────────

  app.post('/albums', async (request, reply) => {
    const name = readName(request.body?.name)
    if (!name) {
      return fail(reply, 400, 'BAD_REQUEST', `name is required and must be under ${MAX_NAME_LENGTH} characters.`)
    }

    const now = nextUpdatedAt(db, 'albums')
    const id = crypto.randomUUID()
    db.prepare(
      'INSERT INTO albums (id, name, cover_asset_id, created_at, deleted_at, updated_at) VALUES (?, ?, NULL, ?, NULL, ?)',
    ).run(id, name, now, now)

    return ok(reply, serializeAlbum(readAlbum(db, id)), 201)
  })

  app.get('/albums/:id', async (request, reply) => {
    const album = readAlbum(db, request.params.id)
    if (!album) return fail(reply, 404, 'NOT_FOUND', 'No such album.')
    return ok(reply, serializeAlbum(album))
  })

  app.patch('/albums/:id', async (request, reply) => {
    const album = readAlbum(db, request.params.id)
    if (!album) return fail(reply, 404, 'NOT_FOUND', 'No such album.')
    if (album.deleted_at !== null) {
      return fail(reply, 410, 'ALBUM_DELETED', 'That album has been deleted.')
    }

    let name = album.name
    if (request.body?.name !== undefined) {
      name = readName(request.body.name)
      if (!name) {
        return fail(reply, 400, 'BAD_REQUEST', `name must be non-blank and under ${MAX_NAME_LENGTH} characters.`)
      }
    }

    let cover = album.cover_asset_id
    if (request.body?.coverAssetId !== undefined) {
      cover = request.body.coverAssetId
      if (cover !== null) {
        const asset = db.prepare('SELECT id FROM assets WHERE id = ? AND deleted_at IS NULL').get(cover)
        if (!asset) return fail(reply, 404, 'NOT_FOUND', 'No such asset for the cover.')
      }
    }

    const now = nextUpdatedAt(db, 'albums')
    db.prepare('UPDATE albums SET name = ?, cover_asset_id = ?, updated_at = ? WHERE id = ?').run(
      name,
      cover,
      now,
      album.id,
    )
    return ok(reply, serializeAlbum(readAlbum(db, album.id)))
  })

  /**
   * Tombstoned, never dropped — the same rule assets follow (§2, §7).
   *
   * The membership rows are left exactly as they are. Rewriting thousands of
   * them to say "the album they belong to is gone" would be a write storm that
   * tells the client nothing it cannot work out from the album itself.
   */
  app.delete('/albums/:id', async (request, reply) => {
    const album = readAlbum(db, request.params.id)
    if (!album) return fail(reply, 404, 'NOT_FOUND', 'No such album.')
    if (album.deleted_at !== null) return ok(reply, { id: album.id, deleted: true })

    const now = nextUpdatedAt(db, 'albums')
    db.prepare('UPDATE albums SET deleted_at = ?, updated_at = ? WHERE id = ?').run(now, now, album.id)
    return ok(reply, { id: album.id, deleted: true, deletedAt: now })
  })

  // ─── Membership ───────────────────────────────────────────────────────────

  app.post('/albums/:id/items', async (request, reply) => {
    const album = readAlbum(db, request.params.id)
    if (!album) return fail(reply, 404, 'NOT_FOUND', 'No such album.')
    if (album.deleted_at !== null) {
      return fail(reply, 410, 'ALBUM_DELETED', 'That album has been deleted.')
    }

    const assetIds = request.body?.assetIds
    if (!Array.isArray(assetIds) || assetIds.length === 0) {
      return fail(reply, 400, 'BAD_REQUEST', 'assetIds must be a non-empty array.')
    }
    if (assetIds.length > config.maxCheckHashes) {
      return fail(
        reply,
        400,
        'TOO_MANY',
        `At most ${config.maxCheckHashes} assets per request.`,
      )
    }

    // Every id is checked before anything is written: half an album is worse
    // than a refusal the client can retry.
    const live = db.prepare('SELECT id FROM assets WHERE id = ? AND deleted_at IS NULL')
    const missing = assetIds.filter((id) => !live.get(id))
    if (missing.length > 0) {
      return fail(reply, 404, 'NOT_FOUND', 'Some assets do not exist or are in the trash.', {
        missing,
      })
    }

    // Re-adding something that was taken out clears the tombstone rather than
    // failing on the primary key.
    const upsert = db.prepare(`
      INSERT INTO album_items (album_id, asset_id, added_at, removed_at, updated_at)
      VALUES (?, ?, ?, NULL, ?)
      ON CONFLICT(album_id, asset_id) DO UPDATE SET
        added_at   = excluded.added_at,
        removed_at = NULL,
        updated_at = excluded.updated_at
    `)

    db.exec('BEGIN')
    try {
      for (const assetId of assetIds) {
        // One timestamp per row, so each addition is its own cursor step and a
        // page boundary can never land in the middle of a tie.
        const now = nextUpdatedAt(db, 'album_items')
        upsert.run(album.id, assetId, now, now)
      }
      db.exec('COMMIT')
    } catch (err) {
      db.exec('ROLLBACK')
      throw err
    }

    return ok(reply, { albumId: album.id, added: assetIds.length })
  })

  app.delete('/albums/:id/items/:assetId', async (request, reply) => {
    const { id, assetId } = request.params
    const row = db
      .prepare('SELECT * FROM album_items WHERE album_id = ? AND asset_id = ?')
      .get(id, assetId)

    if (!row) return fail(reply, 404, 'NOT_FOUND', 'That asset is not in that album.')
    if (row.removed_at !== null) return ok(reply, { albumId: id, assetId, removed: true })

    const now = nextUpdatedAt(db, 'album_items')
    db.prepare(
      'UPDATE album_items SET removed_at = ?, updated_at = ? WHERE album_id = ? AND asset_id = ?',
    ).run(now, now, id, assetId)

    return ok(reply, { albumId: id, assetId, removed: true, removedAt: now })
  })

  /** Shared by both delta endpoints; returns null once it has answered itself. */
  function readPage(request, reply) {
    const since = Number(request.query.since ?? 0)
    if (!Number.isFinite(since) || since < 0) {
      fail(reply, 400, 'BAD_REQUEST', 'since must be a non-negative epoch-ms value.')
      return null
    }

    const requested = Number(request.query.limit ?? config.maxListLimit)
    const limit = Math.min(
      Number.isFinite(requested) && requested > 0 ? Math.trunc(requested) : config.maxListLimit,
      config.maxListLimit,
    )
    return { since, limit }
  }
}

function readAlbum(db, id) {
  return db.prepare('SELECT * FROM albums WHERE id = ?').get(id) ?? null
}

function readName(value) {
  if (typeof value !== 'string') return null
  const name = value.trim()
  if (name.length === 0 || name.length > MAX_NAME_LENGTH) return null
  return name
}

function serializeAlbum(row) {
  if (row.deleted_at !== null) {
    return { id: row.id, deleted: true, deletedAt: row.deleted_at, updatedAt: row.updated_at }
  }
  return {
    id: row.id,
    name: row.name,
    coverAssetId: row.cover_asset_id,
    createdAt: row.created_at,
    deleted: false,
    updatedAt: row.updated_at,
  }
}

function serializeItem(row) {
  return {
    albumId: row.album_id,
    assetId: row.asset_id,
    addedAt: row.added_at,
    removed: row.removed_at !== null,
    removedAt: row.removed_at,
    updatedAt: row.updated_at,
  }
}
