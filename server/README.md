# Kadr Server

Self-hosted photo & video backup — the server half. Node 24 + Fastify + SQLite,
no native modules, no transcoding, no cloud.

Implements the API contract in §9 of the project document. **M1 is complete and
verified** (§14): `curl` can pair, start an upload, send chunks, complete, and
re-download the exact bytes.

---

## Why this shape

The target box is a dual-core Sandy Bridge Pentium with 4 GB RAM and an HDD.
Every choice below follows from that:

| Decision | Reason |
|---|---|
| `node:sqlite` (built in) instead of `better-sqlite3` | No C++ toolchain on the server, no compile step, no rebuild after a Node upgrade. |
| Upload chunks stream straight to disk | A 2 GB video never lands in RAM. Measured peak: **83 MB RSS** while receiving 256 MB. |
| `Range` served from the original bytes | The phone's hardware decoder does the work; the Pentium just reads the file. |
| One ffmpeg worker at `nice -n 19` | Thumbnails must never compete with the API for the two cores or the disk head. |
| WAL + `synchronous=NORMAL` | One writer, crash-safe enough, far fewer fsyncs on a slow disk. |
| `last_seen_at` throttled to one write per minute per device | Keeps the HDD out of the request hot path. |

---

## Requirements

- Node **22.5+** (24 recommended) — `node:sqlite` ships with it
- `ffmpeg` on `PATH` — thumbnails only; everything else works without it
- A disk mounted at `/srv/kadr`

## Run it

```bash
npm install
npm start
```

Create the first account from the console — it should not come from a web form
on a box that is deliberately not exposed to the internet (§13):

```bash
node src/cli.js user add hasan
```

### Configuration

All optional, all environment variables:

| Variable | Default | Notes |
|---|---|---|
| `KADR_DATA_DIR` | `/srv/kadr` (`./data` on Windows) | Blobs, thumbs, trash, database |
| `KADR_DB_PATH` | `$KADR_DATA_DIR/kadr.db` | |
| `KADR_HOST` | `0.0.0.0` | Set to `127.0.0.1` when Caddy fronts it |
| `KADR_PORT` | `8787` | |
| `KADR_MIN_FREE_BYTES` | 1 GiB | Reserve kept free; a session that would eat into it is refused with `507` before any bytes are sent |
| `KADR_TRUST_PROXY` | `loopback` | Whose `X-Forwarded-For` to believe. See "Behind a proxy" |

---

## Tests

Both suites drive the real HTTP surface with `curl` — no mocks.

```bash
bash test/e2e.sh
```

94 checks: sign-in and lockout, token revocation, chunked upload with resume,
idempotent re-sends, range gaps, hash mismatch, short chunks, dedupe, `Range`
correctness, trash round trip, albums.

```bash
bash test/restart.sh
```

Kills the server between chunks on an isolated port and proves the upload
resumes from the SQLite row and the `.part` file alone.

```bash
bash test/hardening.sh
```

16 checks: a full disk is refused before a byte is sent and leaves no partial
file behind, the same upload succeeds once there is room, migrations do not
re-run when the database is reopened, and the sign-in throttle locks out one
address without touching another.

```bash
node test/soak.mjs          # 10,000 assets
COUNT=1000 node test/soak.mjs
```

The §15 soak. Real uploads through the real API — check, session, chunk,
complete — while watching memory, latency and database growth.

### Where it stands at 10,000 assets

| | |
|---|---|
| Upload rate | 246 assets/sec (41 s for 10,000) |
| Peak RSS | **184 MB** against the 300 MB budget |
| Database | 7.8 MB |
| 500-hash dedupe probe | 5–6 ms |
| First sync page | 7 ms |
| Full delta sync | 144 ms over 21 pages |

Six uploads run concurrently, which is more than one phone would do — the
contention is deliberate, and it is what found the pagination bug below.

---

## API quick reference

Base path `/api/v1`. Responses are `{ "data": ... }` or `{ "error": { "code", "message" } }`.

### Sign in

```bash
curl -X POST localhost:8787/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"hasan","password":"…","deviceName":"Pixel 8"}'
```

Anyone who signs in sees the same library (§16). The password is exchanged for a
device token here and never sent again. Five wrong passwords from one address
trigger a 15-minute lockout — see "Behind a proxy" for what "address" means once
Caddy is in front.

### Ask before sending

```bash
curl -X POST localhost:8787/api/v1/assets/check \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"hashes":["<sha256>"]}'
```

### Upload

```bash
# 1. open a session (returns uploadId; or alreadyExists if the blob is known)
curl -X POST localhost:8787/api/v1/uploads \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sha256":"…","sizeBytes":9961472,"filename":"clip.mp4","mimeType":"video/mp4"}'
```

```bash
# 2. send a chunk (repeat; re-sending a held range is a no-op)
curl -X PATCH "localhost:8787/api/v1/uploads/$ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  -H 'Content-Range: bytes 0-4194303/9961472' \
  --data-binary @chunk0.bin
```

```bash
# 3. after a crash, ask where you left off
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/uploads/$ID"
```

```bash
# 4. seal it — the server re-hashes and refuses a mismatch
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8787/api/v1/uploads/$ID/complete"
```

### Library

```bash
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/assets?since=0&limit=500"
```

```bash
curl -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-1048575' \
  -o head.bin "localhost:8787/api/v1/assets/$ASSET/file"
```

### Albums (§16.6)

Manual and shared: §16 already made the library shared, so an album that lived
on one phone only would contradict the library it belongs to.

```bash
# create, fill, and read back
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Georgia 2024"}' "localhost:8787/api/v1/albums"

curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"assetIds\":[\"$ASSET\"]}" "localhost:8787/api/v1/albums/$ALBUM/items"

curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/albums?since=0"
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/album-items?since=0"
```

Two delta streams, not one. Albums and membership move at completely different
rates — renaming an album should not drag five thousand membership rows across
the wire — and each keeps its own `updated_at` counter, so an upload does not
push every album cursor forward.

There is no "album contents" endpoint on purpose. Clients already mirror the
library, and `album-items` gives them the relationship; the contents are a join
they can do locally. Trash needed an endpoint of its own only because delta sync
carries nothing there but tombstones.

Removing a photo from an album **tombstones** the row (`removed: true`) instead
of deleting it, for the same reason assets are tombstoned: a row that is simply
gone is not a change any client can see, so the photo would sit in that album
forever on every phone that had already synced it. Adding it back clears the
tombstone rather than colliding with the primary key.

Deleting an **asset** leaves its album rows alone, so restoring it from the
trash puts it back in the albums it was in. Freeing local space never touches
membership at all — that is a phone-side deletion and the server still has the
file.

### Behind a proxy

Caddy terminates TLS and proxies in from `127.0.0.1`, so the API must be told
whose `X-Forwarded-For` to believe or every request looks like it came from the
same address — and §13's per-IP sign-in throttle collapses into one global
counter, where a single wrong password locks out every phone in the house.

`KADR_TRUST_PROXY` defaults to `loopback`: only a proxy on this machine is
believed. Deliberately not `true` — if the API is ever reachable directly, a
header from the LAN must not be able to forge an address. `hardening.sh` §4
pins both halves: one address gets locked out, another is unaffected.

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `ALBUM_DELETED` | 410 | The album is tombstoned; it cannot be edited |
| `TOO_MANY` | 400 | More than 500 assets in one album request |
| `RANGE_GAP` | 409 | Chunk starts past `receivedBytes`; resume from there |
| `SESSION_RESET` | 409 | The partial file is gone; restart at byte 0 |
| `LENGTH_MISMATCH` | 400 | Fewer bytes arrived than `Content-Range` promised |
| `HASH_MISMATCH` | 409 | Reassembled file hashes wrong; session reset to 0 |
| `INCOMPLETE` | 409 | `complete` called before all bytes arrived |
| `DISK_FULL` | 507 | `ENOSPC` — surfaced, never a silent stall |
| `LOGIN_LOCKED` | 429 | Five failed sign-in attempts from this IP |
| `THUMB_UNAVAILABLE` | 503 | ffmpeg missing or the frame could not be extracted |

---

## The bug the soak found

At 10,000 assets the delta sync returned **9,995 rows**. Five photos a client
would never have seen.

`GET /assets?since=X` pages with `WHERE updated_at > ? ORDER BY updated_at`, and
the cursor is the last row's `updated_at`. Several assets completing inside the
same millisecond share a timestamp; when such a group straddles a page boundary,
`> cursor` skips whatever is left of it. Nothing errors, nothing logs — the
library is just quietly short.

The fix keeps §9's contract intact. `updated_at` is now handed out by
`nextUpdatedAt()` as `max(now, highest + 1)`, so ties cannot happen and the
column still means what §8 says it means. The soak asserts the full page-through
returns exactly as many rows as were uploaded, which is the assertion that
caught it.

## Two behaviours worth knowing

**A trashed asset counts as missing.** `/assets/check` only reports live assets
as present. If a soft-deleted asset were reported as present, the phone could
mark it `VERIFIED`, free up local space, and then lose the file for good when
the trash purges at 30 days. Re-uploading a trashed asset restores the existing
row instead of creating a second one.

**`complete` is the only thing that trusts nothing.** The client's declared hash
is treated as a claim until the server re-reads the assembled file and hashes it
itself. A mismatch resets the session to zero rather than writing a bad blob.

---

## Deploying on Ubuntu

Clone this repository on the server and run the installer:

```bash
sudo bash server/deploy/install.sh
```

It installs Node, ffmpeg and Caddy, creates the `kadr` service account, copies
the app to `/opt/kadr/server`, installs the systemd unit, writes the Caddyfile,
opens 443 and then checks that the service actually answers. Running it again
upgrades in place; it never touches `/srv/kadr`, where the photos live.

`KADR_SITE=photos.lan` overrides the hostname Caddy serves, `KADR_DATA_DIR`
the data directory.

**Node.** The installer refuses anything older than 22.13 and installs 24.x
instead. `node:sqlite` is the whole database layer, and 22.x builds below that
keep it behind `--experimental-sqlite`, which the unit does not pass — an
out-of-date Node does not degrade, it fails to boot.

Three things the script deliberately leaves to a human, and prints at the end:
creating the first account (the password is typed at a terminal), pointing the
hostname at the box, and installing Caddy's root certificate on the phone.

Doing it by hand instead:

```bash
sudo cp deploy/kadr.service /etc/systemd/system/
sudo systemctl enable --now kadr
journalctl -u kadr -f          # watch it come up
```

TLS terminates at Caddy (see `deploy/Caddyfile`), which is why the unit binds
Kadr to `127.0.0.1`. Do not port-forward this to the open internet — reach it
from outside over Tailscale or WireGuard (§13).

---

## Not done yet

- `GET /assets/{id}/thumb` works, but generation needs `ffmpeg` installed; the
  low-priority background pre-generation worker and its `--dry-run` mode (§17)
  are not built. Thumbnails are generated lazily on first request.
- No QR page for the server address yet; it is typed in by hand (§12 onboarding).
- No TLS in-process; Caddy is expected to front it.
