import crypto from 'node:crypto'
import { config } from './config.js'
import { fail } from './errors.js'

export function sha256hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

/**
 * Issues a device token for a signed-in user.
 *
 * The token, not the password, is what every later request carries — so a
 * phone never stores the password, and one device can be revoked without
 * touching the others.
 */
export function createDevice(db, userId, name) {
  const id = crypto.randomUUID()
  const token = crypto.randomBytes(32).toString('base64url')
  const now = Date.now()

  db.prepare(
    `INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id)
     VALUES (?, ?, ?, ?, ?, ?)`,
  ).run(id, name, sha256hex(token), now, now, userId)

  return { deviceId: id, token }
}

/**
 * Per-IP throttle for sign-in: 5 failures, then a 15 minute lockout (§13).
 * In memory on purpose — a restart clearing it is acceptable, and it keeps the
 * HDD out of the hot path.
 */
export function createLoginLimiter() {
  const attempts = new Map()

  return {
    check(ip) {
      const record = attempts.get(ip)
      if (!record) return { allowed: true }
      if (record.lockedUntil && record.lockedUntil > Date.now()) {
        return { allowed: false, retryAfterMs: record.lockedUntil - Date.now() }
      }
      return { allowed: true }
    },
    fail(ip) {
      const record = attempts.get(ip) ?? { count: 0, lockedUntil: 0 }
      if (record.lockedUntil && record.lockedUntil <= Date.now()) {
        record.count = 0
        record.lockedUntil = 0
      }
      record.count += 1
      if (record.count >= config.loginMaxAttempts) {
        record.lockedUntil = Date.now() + config.loginLockoutMs
      }
      attempts.set(ip, record)
    },
    succeed(ip) {
      attempts.delete(ip)
    },
  }
}

/**
 * Bearer auth hook. Tokens are stored hashed (§13) so a stolen database does
 * not hand over working credentials.
 */
export function createAuthHook(db) {
  const lastSeenWrites = new Map()

  return async function requireDevice(request, reply) {
    const header = request.headers.authorization || ''
    const match = /^Bearer\s+(\S+)$/.exec(header)
    if (!match) {
      return fail(reply, 401, 'UNAUTHORIZED', 'Missing or malformed Authorization header.')
    }

    const device = db
      .prepare('SELECT * FROM devices WHERE token_hash = ?')
      .get(sha256hex(match[1]))

    if (!device || device.revoked) {
      return fail(reply, 401, 'UNAUTHORIZED', 'Invalid or revoked token. Sign in again.')
    }

    request.device = device

    const now = Date.now()
    const last = lastSeenWrites.get(device.id) ?? 0
    if (now - last > config.lastSeenThrottleMs) {
      lastSeenWrites.set(device.id, now)
      db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(now, device.id)
    }
  }
}
