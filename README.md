# Kadr

Self-hosted photo & video backup for Android, backed by a server on your own
home network. No cloud, no subscription, no account. Full spec in the project
context document (§1–§17).

```
galery app/
├── server/          Node 24 + Fastify + SQLite  —  M1 complete ✅
└── android/         Kotlin + Compose            —  M2 complete ✅
```

## Status against §14

| # | Milestone | State |
|---|---|---|
| M1 | Server skeleton | **Done** — 94 curl checks green, plus hardening and restart-mid-upload suites |
| M2 | Android index | **Done** — verified on an API 37 device: scan → Room → upload → byte-identical round trip |
| M3 | Backup engine | **Nearly done** — loop, batched dedupe, retries, notification all working and tested; reboot-resume not signed off (see below) |
| M4 | Gallery UI | **Done** — merged local+server timeline, month dividers, viewer with zoom, paging and drag-to-dismiss, shared-element transitions |
| M5 | Video | **Written, not signed off** — player, cache, gestures and grid preview built; the emulator's software decoder cannot play anything, so playback needs a real device |
| M6 | Polish | **Done** — Backup status, Settings and Trash screens, free-up-space with server re-confirmation, haptics, Material You opt-in, reduce-motion |
| M7 | Hardening | **Done** — disk-full refused up front, migration tests on both sides, 10,000-asset soak green |

## Verified so far

- 94 `curl` checks against the live API: sign-in, chunked upload with resume,
  idempotent re-sends, hash mismatch, dedupe, `Range` correctness, trash round
  trip, albums, revocation.
- The server survives being killed mid-upload and resumes from the SQLite row
  and the partial file alone.
- Peak server memory during a 256 MB upload: **83 MB RSS** (§15 budget: 300 MB).
- On device: MediaStore scan populates Room, re-scanning adds nothing, a 5 MB
  file uploads in chunks, and the bytes downloaded back hash identically.
- §17's five failure cases pass against a scripted server: connection dropped
  mid-chunk, server restart, hash mismatch, duplicate file, full disk. A full
  server stops the run instead of failing every remaining file, and costs no
  photo one of its six attempts.
- The device token is unreadable in the app's own files, survives a cold start,
  and goes away with the Keystore key when the user signs out.
- A 216-file batch ran end to end through WorkManager, across a device reboot,
  with no duplicates.
- Free-up-space refuses to delete a file the server cannot vouch for, and does
  not mark anything freed while the file is still on disk.
- Trash round trip driven from the app: an asset deleted on the server appears
  with a 29-day countdown, and restoring it from the phone empties the trash.
- **10,000 assets uploaded through the real API in 41 s**, peak server memory
  184 MB against the 300 MB budget, full delta sync of the library in 144 ms.
- A v1 database survives the upgrade to v2 and v3 with its rows intact.
- The timeline is read a page at a time, and paging a library full of photos
  that share a capture time shows every one of them exactly once — the case
  where a `LIMIT/OFFSET` window silently drops or repeats rows.

## What the soak caught

At 10,000 assets the delta sync returned 9,995 rows — five photos a client
would never have seen, with nothing logged and no error raised. Assets
completing inside the same millisecond shared an `updated_at`, and a group
straddling a page boundary was partly skipped by `WHERE updated_at > cursor`.
The cursor is now strictly monotonic, so ties cannot happen; §9's contract is
unchanged. Details in [server/README.md](server/README.md).

## Not signed off

Two things are built but unproven, both blocked on the same thing — a real phone:

**M3, resume after a reboot.** Room state and the periodic schedule both
survive, and the boot receiver does enqueue a resume, but nothing actually
uploads until the app is opened. Three real bugs were found and fixed chasing
this; the remaining cause is unidentified.

**M5, video playback.** The emulator's software H.264 decoder fails under
ExoPlayer for local and remote clips alike. The clip decodes fine outside
ExoPlayer and the server serves authenticated `Range` requests correctly, so
what is untested is playback on a hardware decoder.

Details in [android/README.md](android/README.md#known-gaps).

## Decisions taken (§16)

| # | Question | Answer |
|---|---|---|
| 1 | Compose vs XML | **Compose** — in use |
| 2 | Go vs Node | **Node 24 + Fastify** — chosen so the API could be built and curl-verified on the dev machine straight away |
| 5 | Encryption at rest | Plain blobs for v1; LUKS on the disk itself |

Raised outside §16 and now settled: `androidx.security.crypto` is deprecated, so
the device token moved to a Keystore-backed wrapper of our own. Only the token is
encrypted, and phones that were already paired migrate across without signing in
again.

**§16.6, the album model, is decided**: manual albums, held on the server and
shared by every phone that signs in — the same shape §16 already gave the
library. Mirroring phone folders was considered and dropped: `relativePath`
never reaches the server, old assets could never be backfilled, and a photo
whose local copy had been freed would vanish from its folder. The server half is
built; the Android half is not.

Still open: disk capacity (§16.3), number of phones (§16.4).

## Getting started

```bash
cd server && npm install && npm start
```

Then build and install the app — see [android/README.md](android/README.md).
Server details and the API reference are in [server/README.md](server/README.md).
