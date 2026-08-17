import { DatabaseSync } from 'node:sqlite'
import fs from 'node:fs'
import path from 'node:path'
import { config } from './config.js'

const MIGRATIONS = [
  // v1 — initial schema (§8)
  (db) => {
    db.exec(`
      CREATE TABLE devices (
        id           TEXT PRIMARY KEY,
        name         TEXT NOT NULL,
        token_hash   TEXT NOT NULL UNIQUE,
        created_at   INTEGER NOT NULL,
        last_seen_at INTEGER,
        revoked      INTEGER NOT NULL DEFAULT 0
      );

      CREATE TABLE assets (
        id           TEXT PRIMARY KEY,
        sha256       TEXT NOT NULL UNIQUE,
        size_bytes   INTEGER NOT NULL,
        mime_type    TEXT NOT NULL,
        filename     TEXT NOT NULL,
        captured_at  INTEGER,
        uploaded_at  INTEGER NOT NULL,
        width        INTEGER,
        height       INTEGER,
        duration_ms  INTEGER,
        orientation  INTEGER NOT NULL DEFAULT 0,
        lat          REAL,
        lon          REAL,
        device_id    TEXT REFERENCES devices(id),
        deleted_at   INTEGER,
        updated_at   INTEGER NOT NULL
      );

      CREATE INDEX idx_assets_captured ON assets(captured_at DESC);
      CREATE INDEX idx_assets_updated  ON assets(updated_at);

      CREATE TABLE upload_sessions (
        id             TEXT PRIMARY KEY,
        sha256         TEXT NOT NULL,
        expected_size  INTEGER NOT NULL,
        received_bytes INTEGER NOT NULL DEFAULT 0,
        filename       TEXT NOT NULL,
        mime_type      TEXT NOT NULL,
        captured_at    INTEGER,
        width          INTEGER,
        height         INTEGER,
        duration_ms    INTEGER,
        orientation    INTEGER NOT NULL DEFAULT 0,
        lat            REAL,
        lon            REAL,
        device_id      TEXT NOT NULL REFERENCES devices(id),
        created_at     INTEGER NOT NULL,
        expires_at     INTEGER NOT NULL
      );

      CREATE INDEX idx_sessions_lookup  ON upload_sessions(device_id, sha256);
      CREATE INDEX idx_sessions_expires ON upload_sessions(expires_at);

      CREATE TABLE pair_codes (
        code_hash  TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at    INTEGER
      );
    `)
  },

  // v2 — accounts.
  //
  // The 6-digit pairing code is replaced by a username and password: anyone who
  // installs the app and signs in sees the same library. A login still ends in
  // a device token, so nothing downstream changes — only how the token is
  // obtained.
  (db) => {
    db.exec(`
      CREATE TABLE users (
        id            TEXT PRIMARY KEY,
        username      TEXT NOT NULL COLLATE NOCASE UNIQUE,
        password_hash TEXT NOT NULL,
        created_at    INTEGER NOT NULL,
        updated_at    INTEGER NOT NULL
      );

      ALTER TABLE devices ADD COLUMN user_id TEXT REFERENCES users(id);

      DROP TABLE pair_codes;
    `)

    // Any device paired under the old scheme belongs to nobody. Rather than
    // leave tokens floating without an account behind them, retire them and
    // make those phones sign in again.
    db.exec('UPDATE devices SET revoked = 1')
  },

  // v3 — albums (§16.6).
  //
  // Manual, server-side and shared, because §16 already made the library
  // shared: an album that only existed on one phone would contradict the
  // library it belongs to.
  //
  // Membership is tombstoned rather than deleted, for the same reason assets
  // are. Delta sync carries changes, and a row that is simply gone is not a
  // change any client can see — the photo would stay in the album forever on
  // every phone that had already synced it.
  (db) => {
    db.exec(`
      CREATE TABLE albums (
        id             TEXT PRIMARY KEY,
        name           TEXT NOT NULL,
        cover_asset_id TEXT REFERENCES assets(id),
        created_at     INTEGER NOT NULL,
        deleted_at     INTEGER,
        updated_at     INTEGER NOT NULL
      );

      CREATE INDEX idx_albums_updated ON albums(updated_at);

      CREATE TABLE album_items (
        album_id   TEXT NOT NULL REFERENCES albums(id),
        asset_id   TEXT NOT NULL REFERENCES assets(id),
        added_at   INTEGER NOT NULL,
        removed_at INTEGER,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (album_id, asset_id)
      );

      CREATE INDEX idx_album_items_updated ON album_items(updated_at);
      CREATE INDEX idx_album_items_album   ON album_items(album_id);
    `)
  },
]

function migrate(db) {
  const current = db.prepare('PRAGMA user_version').get().user_version
  for (let v = current; v < MIGRATIONS.length; v++) {
    db.exec('BEGIN')
    try {
      MIGRATIONS[v](db)
      db.exec(`PRAGMA user_version = ${v + 1}`)
      db.exec('COMMIT')
    } catch (err) {
      db.exec('ROLLBACK')
      throw err
    }
  }
}

/** Tables that carry their own `updated_at` delta cursor (§9). */
const CURSOR_TABLES = new Set(['assets', 'albums', 'album_items'])

/**
 * A strictly increasing `updated_at` for the delta-sync cursor (§9).
 *
 * Wall-clock milliseconds are not enough. Several assets completing inside the
 * same millisecond share a timestamp, and if that group straddles a page
 * boundary then `WHERE updated_at > cursor` skips the rest of it — the client
 * pages right past photos it has never seen. A 10,000 asset soak lost five rows
 * exactly this way.
 *
 * Handing out max(now, highest + 1) keeps the column meaning what §8 says it
 * means while making ties impossible, so the published contract does not have
 * to change.
 *
 * Each stream counts on its own table: albums move far less often than assets,
 * and sharing one counter would drag every album cursor forward on every upload.
 */
export function nextUpdatedAt(db, table = 'assets') {
  // The name is interpolated, so it may only ever come from this file.
  if (!CURSOR_TABLES.has(table)) throw new Error(`No delta cursor for table ${table}`)
  const highest = db.prepare(`SELECT MAX(updated_at) AS value FROM ${table}`).get()?.value ?? 0
  return Math.max(Date.now(), highest + 1)
}

export function openDb(dbPath = config.dbPath) {
  fs.mkdirSync(path.dirname(dbPath), { recursive: true })
  const db = new DatabaseSync(dbPath)
  // WAL + synchronous=NORMAL per §6: one writer, slow disk, crash-safe enough.
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    PRAGMA foreign_keys = ON;
    PRAGMA busy_timeout = 5000;
  `)
  migrate(db)
  return db
}
