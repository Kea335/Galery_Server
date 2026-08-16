import crypto from 'node:crypto'
import { promisify } from 'node:util'

const scrypt = promisify(crypto.scrypt)

/**
 * scrypt, from node's own crypto — no new dependency and nothing to compile on
 * the server.
 *
 * N is 16384 rather than the more fashionable 32768 because §4's box is a
 * Sandy Bridge Pentium: at 32768 a login costs it the better part of a second.
 * 16384 still puts a serious price on each guess, and the login endpoint is
 * rate limited on top (§13).
 *
 * The parameters live in the stored string, so they can be raised later without
 * invalidating anyone's password.
 */
const PARAMS = { N: 16384, r: 8, p: 1, keylen: 64 }

export async function hashPassword(password) {
  const salt = crypto.randomBytes(16)
  const derived = await scrypt(password, salt, PARAMS.keylen, {
    N: PARAMS.N,
    r: PARAMS.r,
    p: PARAMS.p,
  })
  return [
    'scrypt',
    PARAMS.N,
    PARAMS.r,
    PARAMS.p,
    salt.toString('hex'),
    derived.toString('hex'),
  ].join('$')
}

export async function verifyPassword(password, stored) {
  const parts = String(stored).split('$')
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false

  const [, n, r, p, saltHex, hashHex] = parts
  const expected = Buffer.from(hashHex, 'hex')
  if (expected.length === 0) return false

  const derived = await scrypt(password, Buffer.from(saltHex, 'hex'), expected.length, {
    N: Number(n),
    r: Number(r),
    p: Number(p),
  })

  return crypto.timingSafeEqual(derived, expected)
}

export async function createUser(db, username, password) {
  const name = normalize(username)
  assertUsername(name)
  assertPassword(password)

  const now = Date.now()
  const id = crypto.randomUUID()
  db.prepare(
    'INSERT INTO users (id, username, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)',
  ).run(id, name, await hashPassword(password), now, now)

  return { id, username: name, createdAt: now }
}

export async function setPassword(db, username, password) {
  assertPassword(password)
  const result = db
    .prepare('UPDATE users SET password_hash = ?, updated_at = ? WHERE username = ?')
    .run(await hashPassword(password), Date.now(), normalize(username))
  return result.changes === 1
}

export function findUser(db, username) {
  return db.prepare('SELECT * FROM users WHERE username = ?').get(normalize(username)) ?? null
}

export function listUsers(db) {
  return db.prepare('SELECT id, username, created_at FROM users ORDER BY username').all()
}

export function userCount(db) {
  return db.prepare('SELECT COUNT(*) AS c FROM users').get().c
}

/**
 * Verifies a login. Always does the scrypt work, even for a username that does
 * not exist, so a wrong name and a wrong password take the same time and the
 * endpoint does not leak which accounts are real.
 */
export async function authenticate(db, username, password) {
  const user = findUser(db, username)
  const stored = user?.password_hash ?? DUMMY_HASH
  const ok = await verifyPassword(password ?? '', stored)
  return ok && user ? user : null
}

function normalize(username) {
  return String(username ?? '').trim().toLowerCase()
}

function assertUsername(name) {
  if (!/^[a-z0-9._-]{2,32}$/.test(name)) {
    throw new Error('Username must be 2–32 characters: letters, digits, dot, dash or underscore.')
  }
}

function assertPassword(password) {
  if (typeof password !== 'string' || password.length < 8) {
    throw new Error('Password must be at least 8 characters.')
  }
}

/**
 * A real hash of a value nobody knows, so the no-such-user path costs exactly
 * what the real path costs.
 */
const DUMMY_HASH =
  'scrypt$16384$8$1$00000000000000000000000000000000$' +
  '0'.repeat(128)
