import path from 'node:path'

// On the target box media lives on a dedicated disk at /srv/kadr (§4).
// On a dev machine we keep everything under ./data so nothing escapes the repo.
const defaultDataDir =
  process.platform === 'win32' ? path.join(process.cwd(), 'data') : '/srv/kadr'

const dataDir = path.resolve(process.env.KADR_DATA_DIR || defaultDataDir)

export const config = {
  version: '0.1.0',

  host: process.env.KADR_HOST || '0.0.0.0',
  port: Number(process.env.KADR_PORT || 8787),

  dataDir,
  blobsDir: path.join(dataDir, 'blobs'),
  thumbsDir: path.join(dataDir, 'thumbs'),
  trashDir: path.join(dataDir, 'trash'),
  tmpDir: path.join(dataDir, 'tmp'),
  dbPath: process.env.KADR_DB_PATH || path.join(dataDir, 'kadr.db'),

  // Sign-in (§13). A password can be guessed at, unlike a one-shot code that
  // expired in five minutes, so the throttle matters more than it used to.
  loginMaxAttempts: 5,
  loginLockoutMs: 15 * 60_000,

  /**
   * Whose `X-Forwarded-For` to believe.
   *
   * Caddy terminates TLS and proxies in from the same machine (see
   * deploy/Caddyfile), so without this every request looks like it came from
   * 127.0.0.1 and the per-IP login throttle above collapses into one global
   * counter — one wrong password would lock out every phone in the house.
   *
   * `loopback` and not `true`: only a proxy on this machine is believed. If the
   * API is ever reachable directly, a header from the LAN cannot forge an
   * address.
   */
  trustProxy: process.env.KADR_TRUST_PROXY || 'loopback',

  // Uploads (§9, §10)
  uploadSessionTtlMs: 7 * 24 * 60 * 60_000,
  maxCheckHashes: 500,
  maxListLimit: 500,

  /**
   * Never let uploads run the disk to zero — SQLite needs room for its WAL and
   * the OS needs room to breathe. A session is refused up front if finishing it
   * would eat into this reserve, which turns "disk full" from a corrupted blob
   * halfway through into a clear error before a single byte is sent (§15).
   */
  minFreeBytes: Number(process.env.KADR_MIN_FREE_BYTES || 1024 * 1024 * 1024),

  // Housekeeping
  trashRetentionMs: 30 * 24 * 60 * 60_000,
  reaperIntervalMs: 60 * 60_000,

  // The server disk is a slow HDD — don't write last_seen_at on every request.
  lastSeenThrottleMs: 60_000,
}
