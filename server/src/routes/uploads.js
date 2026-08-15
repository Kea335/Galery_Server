import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'

import { config } from '../config.js'
import { nextUpdatedAt } from '../db.js'
import { fail, ok } from '../errors.js'
import {
  SHA256_RE,
  blobPath,
  exists,
  fileSize,
  freeBytes,
  hashFile,
  moveIntoPlace,
  partPath,
  trashPath,
} from '../storage.js'

const CONTENT_RANGE_RE = /^bytes (\d+)-(\d+)\/(\d+)$/

export default async function uploadRoutes(app, { db, requireDevice }) {
  app.addHook('preHandler', requireDevice)

  /**
   * §10.3 — ask before sending. The phone batches up to 500 hashes and only
   * uploads what comes back in `missing`.
   *
   * A soft-deleted (trashed) asset counts as MISSING on purpose: if we reported
   * it as present the phone could mark it VERIFIED, free up local space, and
   * then lose the file for good when the trash is purged at 30 days. Re-upload
   * restores the trashed row instead (see /complete).
   */
  app.post('/assets/check', async (request, reply) => {
    const hashes = request.body?.hashes
    if (!Array.isArray(hashes)) {
      return fail(reply, 400, 'BAD_REQUEST', 'Body must be { hashes: [...] }.')
    }
    if (hashes.length > config.maxCheckHashes) {
      return fail(
        reply,
        400,
        'TOO_MANY_HASHES',
        `At most ${config.maxCheckHashes} hashes per call.`,
      )
    }

    const clean = [...new Set(hashes.filter((h) => typeof h === 'string' && SHA256_RE.test(h)))]
    if (clean.length === 0) return ok(reply, { missing: [] })

    const placeholders = clean.map(() => '?').join(',')
    const rows = db
      .prepare(
        `SELECT sha256 FROM assets WHERE deleted_at IS NULL AND sha256 IN (${placeholders})`,
      )
      .all(...clean)

    const present = new Set(rows.map((r) => r.sha256))
    return ok(reply, { missing: clean.filter((h) => !present.has(h)) })
  })

  /**
   * Start (or resume) an upload session.
   */
  app.post('/uploads', async (request, reply) => {
    const b = request.body ?? {}

    if (typeof b.sha256 !== 'string' || !SHA256_RE.test(b.sha256)) {
      return fail(reply, 400, 'BAD_REQUEST', 'sha256 must be a 64-char lowercase hex digest.')
    }
    if (!Number.isSafeInteger(b.sizeBytes) || b.sizeBytes <= 0) {
      return fail(reply, 400, 'BAD_REQUEST', 'sizeBytes must be a positive integer.')
    }
    if (typeof b.filename !== 'string' || !b.filename.trim()) {
      return fail(reply, 400, 'BAD_REQUEST', 'filename is required.')
    }
    if (typeof b.mimeType !== 'string' || !b.mimeType.trim()) {
      return fail(reply, 400, 'BAD_REQUEST', 'mimeType is required.')
    }

    const sha256 = b.sha256
    const existing = db
      .prepare('SELECT id FROM assets WHERE sha256 = ? AND deleted_at IS NULL')
      .get(sha256)

    if (existing) {
      // Deduplication: the bytes are already on disk, nothing to send.
      return ok(reply, { alreadyExists: true, assetId: existing.id })
    }

    const now = Date.now()
    const resumable = db
      .prepare(
        'SELECT * FROM upload_sessions WHERE device_id = ? AND sha256 = ? AND expires_at > ?',
      )
      .get(request.device.id, sha256, now)

    if (resumable) {
      // A part file that vanished (tmp wiped, disk swapped) means we start over
      // rather than lie about how much we hold.
      const onDisk = await fileSize(partPath(resumable.id))
      let received = resumable.received_bytes
      if (onDisk === null) {
        await createEmptyPart(resumable.id)
        received = 0
        db.prepare('UPDATE upload_sessions SET received_bytes = 0 WHERE id = ?').run(resumable.id)
      } else if (onDisk < received) {
        received = onDisk
        db.prepare('UPDATE upload_sessions SET received_bytes = ? WHERE id = ?').run(
          received,
          resumable.id,
        )
      }
      return ok(reply, { uploadId: resumable.id, receivedBytes: received })
    }

    // Refuse before anything is sent rather than filling the disk and failing
    // halfway through (§15: never a corrupted blob, never a silent stall).
    const free = await freeBytes()
    if (free !== null && free - b.sizeBytes < config.minFreeBytes) {
      return fail(
        reply,
        507,
        'DISK_FULL',
        `Not enough room: ${free} bytes free, this upload needs ${b.sizeBytes} and the server keeps ${config.minFreeBytes} in reserve.`,
        { freeBytes: free, requiredBytes: b.sizeBytes, reserveBytes: config.minFreeBytes },
      )
    }

    const id = crypto.randomUUID()
    try {
      await createEmptyPart(id)
    } catch (err) {
      return diskError(reply, err)
    }

    db.prepare(
      `INSERT INTO upload_sessions
         (id, sha256, expected_size, received_bytes, filename, mime_type, captured_at,
          width, height, duration_ms, orientation, lat, lon, device_id, created_at, expires_at)
       VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      sha256,
      b.sizeBytes,
      b.filename.trim().slice(0, 255),
      b.mimeType.trim().slice(0, 128),
      intOrNull(b.capturedAt),
      intOrNull(b.width),
      intOrNull(b.height),
      intOrNull(b.durationMs),
      intOrNull(b.orientation) ?? 0,
      numOrNull(b.lat),
      numOrNull(b.lon),
      request.device.id,
      now,
      now + config.uploadSessionTtlMs,
    )

    return ok(reply, { uploadId: id, receivedBytes: 0 }, 201)
  })

  /**
   * Resume probe (§9) — the phone calls this after a crash or reboot.
   */
  app.get('/uploads/:id', async (request, reply) => {
    const session = getSession(db, request.params.id, request.device.id)
    if (!session) return fail(reply, 404, 'NO_SESSION', 'Upload session not found or expired.')
    return ok(reply, {
      uploadId: session.id,
      receivedBytes: session.received_bytes,
      expectedSize: session.expected_size,
      sha256: session.sha256,
    })
  })

  /**
   * Receive one chunk. Idempotent: re-sending a range we already hold is a no-op.
   */
  app.patch('/uploads/:id', async (request, reply) => {
    const session = getSession(db, request.params.id, request.device.id)
    if (!session) {
      drain(request)
      return fail(reply, 404, 'NO_SESSION', 'Upload session not found or expired.')
    }

    const header = request.headers['content-range']
    const match = header && CONTENT_RANGE_RE.exec(header)
    if (!match) {
      drain(request)
      return fail(
        reply,
        400,
        'BAD_CONTENT_RANGE',
        'Content-Range header must look like "bytes 0-8388607/52428800".',
      )
    }

    const start = Number(match[1])
    const end = Number(match[2])
    const total = Number(match[3])
    const received = session.received_bytes

    if (total !== session.expected_size) {
      drain(request)
      return fail(
        reply,
        400,
        'SIZE_MISMATCH',
        `Content-Range total ${total} does not match the declared size ${session.expected_size}.`,
      )
    }
    if (start > end || end >= total) {
      drain(request)
      return fail(reply, 400, 'BAD_CONTENT_RANGE', 'Range is outside the declared file size.')
    }
    if (start > received) {
      drain(request)
      return fail(
        reply,
        409,
        'RANGE_GAP',
        `Cannot start at ${start}; only ${received} bytes are held. Resume from there.`,
        { receivedBytes: received },
      )
    }
    if (end < received) {
      // We already hold this range in full — a duplicate retry. Say so and move on.
      drain(request)
      return ok(reply, { receivedBytes: received, duplicate: true })
    }

    const expectedLength = end - start + 1
    const target = partPath(session.id)

    if (!(await exists(target))) {
      drain(request)
      db.prepare('UPDATE upload_sessions SET received_bytes = 0 WHERE id = ?').run(session.id)
      await createEmptyPart(session.id)
      return fail(reply, 409, 'SESSION_RESET', 'Partial file was lost; restart from byte 0.', {
        receivedBytes: 0,
      })
    }

    let written = 0
    const counter = new Transform({
      transform(chunk, _enc, cb) {
        written += chunk.length
        cb(null, chunk)
      },
    })

    try {
      await pipeline(
        request.body,
        counter,
        fs.createWriteStream(target, { flags: 'r+', start }),
      )
    } catch (err) {
      // A short/aborted chunk may have overwritten bytes we previously trusted,
      // so rewind the watermark to the start of this chunk and let it be resent.
      const rewound = Math.min(received, start)
      db.prepare('UPDATE upload_sessions SET received_bytes = ? WHERE id = ?').run(
        rewound,
        session.id,
      )
      if (err.code === 'ENOSPC') return diskError(reply, err)
      request.log.warn({ err, uploadId: session.id }, 'chunk write failed')
      return fail(reply, 400, 'CHUNK_FAILED', 'Chunk transfer failed; resume from receivedBytes.', {
        receivedBytes: rewound,
      })
    }

    if (written !== expectedLength) {
      const rewound = Math.min(received, start)
      db.prepare('UPDATE upload_sessions SET received_bytes = ? WHERE id = ?').run(
        rewound,
        session.id,
      )
      return fail(
        reply,
        400,
        'LENGTH_MISMATCH',
        `Content-Range promised ${expectedLength} bytes but ${written} arrived.`,
        { receivedBytes: rewound },
      )
    }

    const next = Math.max(received, end + 1)
    db.prepare('UPDATE upload_sessions SET received_bytes = ? WHERE id = ?').run(next, session.id)
    return ok(reply, { receivedBytes: next })
  })

  /**
   * Seal the upload. The server re-hashes what it assembled — the client's word
   * is never enough (§9).
   */
  app.post('/uploads/:id/complete', async (request, reply) => {
    const session = getSession(db, request.params.id, request.device.id)
    if (!session) return fail(reply, 404, 'NO_SESSION', 'Upload session not found or expired.')

    const target = partPath(session.id)
    const onDisk = await fileSize(target)

    if (onDisk === null) {
      db.prepare('UPDATE upload_sessions SET received_bytes = 0 WHERE id = ?').run(session.id)
      await createEmptyPart(session.id)
      return fail(reply, 409, 'SESSION_RESET', 'Partial file was lost; restart from byte 0.', {
        receivedBytes: 0,
      })
    }

    if (session.received_bytes !== session.expected_size || onDisk !== session.expected_size) {
      return fail(
        reply,
        409,
        'INCOMPLETE',
        `Have ${Math.min(onDisk, session.received_bytes)} of ${session.expected_size} bytes.`,
        { receivedBytes: Math.min(onDisk, session.received_bytes) },
      )
    }

    const actual = await hashFile(target)
    if (actual !== session.sha256) {
      await fsp.truncate(target, 0)
      db.prepare('UPDATE upload_sessions SET received_bytes = 0 WHERE id = ?').run(session.id)
      return fail(
        reply,
        409,
        'HASH_MISMATCH',
        `Assembled file hashes to ${actual}, expected ${session.sha256}. Session reset.`,
        { receivedBytes: 0 },
      )
    }

    const dest = blobPath(session.sha256)
    try {
      if (await exists(dest)) {
        // Another device won the race; one blob, one hook (§7).
        await fsp.unlink(target)
      } else {
        await moveIntoPlace(target, dest)
      }
    } catch (err) {
      return diskError(reply, err)
    }

    const now = nextUpdatedAt(db)
    const prior = db.prepare('SELECT * FROM assets WHERE sha256 = ?').get(session.sha256)
    let assetId

    if (prior) {
      assetId = prior.id
      if (prior.deleted_at !== null) {
        // Re-uploading something that sits in the trash brings it back rather
        // than creating a second row against the UNIQUE(sha256) constraint.
        const fromTrash = trashPath(session.sha256)
        if (!(await exists(dest)) && (await exists(fromTrash))) {
          await moveIntoPlace(fromTrash, dest)
        }
        db.prepare('UPDATE assets SET deleted_at = NULL, updated_at = ? WHERE id = ?').run(
          now,
          assetId,
        )
      }
    } else {
      assetId = crypto.randomUUID()
      db.prepare(
        `INSERT INTO assets
           (id, sha256, size_bytes, mime_type, filename, captured_at, uploaded_at,
            width, height, duration_ms, orientation, lat, lon, device_id, deleted_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)`,
      ).run(
        assetId,
        session.sha256,
        session.expected_size,
        session.mime_type,
        session.filename,
        session.captured_at,
        now,
        session.width,
        session.height,
        session.duration_ms,
        session.orientation,
        session.lat,
        session.lon,
        session.device_id,
        now,
      )
    }

    db.prepare('DELETE FROM upload_sessions WHERE id = ?').run(session.id)
    return ok(reply, { assetId })
  })
}

function getSession(db, id, deviceId) {
  if (typeof id !== 'string') return null
  return (
    db
      .prepare('SELECT * FROM upload_sessions WHERE id = ? AND device_id = ? AND expires_at > ?')
      .get(id, deviceId, Date.now()) ?? null
  )
}

async function createEmptyPart(uploadId) {
  const target = partPath(uploadId)
  await fsp.mkdir(path.dirname(target), { recursive: true })
  const handle = await fsp.open(target, 'w')
  await handle.close()
}

function drain(request) {
  const body = request.body
  if (body && typeof body.resume === 'function') body.resume()
}

function diskError(reply, err) {
  if (err.code === 'ENOSPC') {
    return fail(reply, 507, 'DISK_FULL', 'The server has run out of disk space.')
  }
  throw err
}

function intOrNull(value) {
  return Number.isFinite(value) ? Math.trunc(value) : null
}

function numOrNull(value) {
  return Number.isFinite(value) ? value : null
}
