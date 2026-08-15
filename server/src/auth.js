import crypto from 'node:crypto'
import { config } from './config.js'
import { fail } from './errors.js'

export function sha256hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

/**
 * Issue a fresh 6-digit pairing code. Any earlier unused code is invalidated,
 * so only one code is ever live at a time. Codes expire in 5 minutes and are
 * single use (§9).
 */
export function generatePairCode(db) {
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0')
  const now = Date.now()
  const expiresAt = now + config.pairCodeTtlMs

  db.prepare('DELETE FROM pair_codes WHERE used_at IS NULL').run()
  db.prepare(
    'INSERT INTO pair_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)',
  ).run(sha256hex(code), now, expiresAt)

  return { code, expiresAt }
}

/**
 * Burn a pairing code. Returns true only if it existed, was unused and unexpired.
 * The UPDATE ... WHERE used_at IS NULL makes this atomic against a double redeem.
 */
export function consumePairCode(db, code) {
  const now = Date.now()
  const res = db
    .prepare(
      'UPDATE pair_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?',
    )
    .run(now, sha256hex(String(code)), now)
  return res.changes === 1
}

export function createDevice(db, name) {
  const id = crypto.randomUUID()
  const token = crypto.randomBytes(32).toString('base64url')
  db.prepare(
    'INSERT INTO devices (id, name, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)',
  ).run(id, name, sha256hex(token), Date.now(), Date.now())
  return { deviceId: id, token }
}

/**
 * Per-IP throttle for /auth/pair: 5 failures, then a 15 minute lockout (§13).
 * In memory on purpose — a restart clearing it is acceptable, and it keeps the
 * HDD out of the hot path.
 */
export function createPairLimiter() {
  const attempts = new Map()

  return {
    check(ip) {
      const rec = attempts.get(ip)
      if (!rec) return { allowed: true }
      if (rec.lockedUntil && rec.lockedUntil > Date.now()) {
        return { allowed: false, retryAfterMs: rec.lockedUntil - Date.now() }
      }
      return { allowed: true }
    },
    fail(ip) {
      const rec = attempts.get(ip) ?? { count: 0, lockedUntil: 0 }
      if (rec.lockedUntil && rec.lockedUntil <= Date.now()) {
        rec.count = 0
        rec.lockedUntil = 0
      }
      rec.count += 1
      if (rec.count >= config.pairMaxAttempts) {
        rec.lockedUntil = Date.now() + config.pairLockoutMs
      }
      attempts.set(ip, rec)
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
      return fail(reply, 401, 'UNAUTHORIZED', 'Invalid or revoked token.')
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
