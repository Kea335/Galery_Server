# Kadr Android

Kotlin + Compose client.

- **M2 is complete and verified on a device** (§14): MediaStore populates Room,
  a debug screen lists the index, and a file uploads with its bytes intact.
- **M3 is complete except one item**: the WorkManager loop, batched dedupe,
  retries with jittered backoff and the foreground notification all work and are
  tested. What is *not* yet working is a fully headless resume after a reboot —
  see "Known gaps".
- **M4 is complete**: one timeline merging local and server photos, month
  dividers, a full-screen viewer with zoom, paging and drag-to-dismiss, and
  shared-element transitions between them.
- **M5 is written but not signed off**: the player, cache, authenticated data
  source, gestures and grid preview are all in place, and the server half is
  verified — but the emulator's software H.264 decoder cannot play anything
  under ExoPlayer, so playback itself is unproven. See "Known gaps".

---

## Toolchain

This project sits on a recent and slightly awkward set of versions, so the
reasoning is worth writing down:

| Piece | Version | Why this one |
|---|---|---|
| JDK | 25 (Android Studio's JBR) | The only JDK on the machine |
| Gradle | 9.5.0 | JDK 25 needs Gradle 9.x |
| AGP | 9.3.1 | Gradle 9 needs AGP 9 |
| Kotlin | 2.4.10 | **AGP 9 applies Kotlin itself.** Kotlin 2.2.x fails with `ApplicationExtensionImpl cannot be cast to BaseExtension`; 2.4 knows how to attach |
| KSP | 2.3.11 | KSP left the `<kotlin>-<ksp>` scheme at 2.3.0 and versions independently now |
| compileSdk | 37 | AndroidX 1.19 / Compose 1.12 refuse to be consumed below it |
| minSdk | 26 | Per §6 |

**Do not add `org.jetbrains.kotlin.android` to the plugins block.** AGP 9 ships
built-in Kotlin support and applying the JetBrains plugin on top is a hard
error. The Compose and serialization compiler plugins still apply normally.

## Build

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Debug builds carry
`usesCleartextTraffic` so you can pair over plain HTTP before installing Caddy's
certificate on the phone; release builds do not.

## Layout

```
app/src/main/java/com/kadr/app/
├── data/
│   ├── local/     Room: LocalAsset, the §8 state machine, DAO, timeline view, albums
│   ├── media/     MediaStoreScanner, Sha256Hasher
│   ├── prefs/     SettingsStore, KeystoreCipher (the token at rest)
│   ├── remote/    Retrofit API, DTOs, ChunkRequestBody, error mapping
│   └── repo/      BackupRepository — scan and upload live here
├── di/            Hilt modules
└── ui/            Compose: pairing screen, debug index screen, theme
```

## What M2 actually does

**Scan.** `MediaStoreScanner` walks images and video. The identity key is
`(mediaStoreId, sizeBytes, dateModified)`; when the last two move, the file was
edited, so the cached hash is dropped and the row restarts the state machine.
Capture dates fall back EXIF → `dateTaken` → `dateModified` → now, exactly as
§17 asks — which means a first scan opens one stream per image and is the slow
part of the run.

**Upload.** `BackupRepository.upload()` runs hash → `/assets/check` →
`/uploads` → chunked `PATCH` → `/complete`. It resumes from whatever the server
says it holds, and treats `RANGE_GAP` / `SESSION_RESET` as instructions rather
than failures. `HASH_MISMATCH` clears the cached digest so the next attempt
recomputes it.

Chunks stream from the `content://` URI 64 KB at a time — a 4 GB video never
lands in the heap.

## Tests

`BackupFlowTest` is the M2 acceptance test. It seeds its own images into
MediaStore (never touching real photos), scans, uploads a file deliberately
larger than one 4 MB chunk, then downloads it back and compares hashes.

The emulator cannot reach the host on `10.0.2.2` behind Windows Firewall, so
tunnel over adb first:

```bash
adb reverse tcp:8787 tcp:8787
```

Then pass an account the server knows (`node src/cli.js` creates the first one):

```bash
adb shell am instrument -w -e kadrServerUrl http://127.0.0.1:8787 -e kadrUser tester -e kadrPassword secret -e class com.kadr.app.BackupFlowTest com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`SettingsStoreTest` and `LegacyPrefsMigrationTest` need no server at all, and
neither do `UploadFailureTest` or `MigrationTest`:

```bash
adb shell am instrument -w -e class com.kadr.app.SettingsStoreTest,com.kadr.app.LegacyPrefsMigrationTest,com.kadr.app.UploadFailureTest,com.kadr.app.MigrationTest com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`SeedMediaTest` is a fixture, not a test: it plants four demo images and leaves
them, so the app can be driven by hand against something real.

```bash
adb shell am instrument -w -e class com.kadr.app.SeedMediaTest com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## Reading the timeline a page at a time (§15)

The grid, the header count and the viewer all read one `@DatabaseView`,
`timeline_items`, which is the local-plus-server union that used to live inline
in the DAO. Three readers of one merged query is exactly the shape that rots
when it is copy-pasted, and Room can create a view for us.

`GalleryDao.pagingTimeline()` hands back a `PagingSource`, so opening the app
reads two screens of photos rather than the whole library. Month dividers are
folded in with `insertSeparators` instead of being built in memory, and the
"N photos" line is a `COUNT(*)` rather than a list length.

**The ordering had to become total.** It used to be `ORDER BY capturedAt DESC`,
which is fine when you read everything at once — but a page is a `LIMIT/OFFSET`
window, and two rows the database may return in either order can then land on
two pages or on none. A burst of shots shares a capture time, so this is not a
rare case. `ORDER BY capturedAt DESC, itemKey DESC` makes the window
deterministic; `TimelinePagingTest` pins it by stacking ties across every page
boundary.

**The viewer opens on a photo, not on a position.** A position taken from a
half-loaded list points somewhere else as soon as more of it is read, so the
route carries the item key and the viewer asks the database where that key sits
(`positionOf`, counting what sorts ahead of it under the same ordering). Its
pager keeps placeholders **on** — it opens at page 4,000 without having read
anything around it. The grid keeps them off, because separators cannot decide
where a month begins when the rows either side are null.

## Albums (§16.6)

Albums belong to the server, so this side is a mirror plus a way to ask the
server to change: `remote_albums` and `album_items`, filled by two delta streams
of their own. Every edit — create, rename, delete, add, remove — goes to the
server first and syncs back rather than writing locally and hoping. Two phones
share these albums, and a local guess the server refused would show one of them
something untrue.

An album's contents are a **local join**, not a request: the library is mirrored
already and membership arrives on its own stream, so `AlbumDao.pagingAlbum()` is
the timeline view with a join in front of it. That means it inherits the
timeline's hazard and its fix — `ORDER BY capturedAt DESC, itemKey DESC`, because
a page is a `LIMIT/OFFSET` window and a burst of shots shares a capture time. A
burst is exactly what someone puts in an album, so `AlbumPagingTest` stacks ties
across every page boundary.

Two consequences worth stating out loud:

- **A photo that is not backed up yet cannot go in an album.** The album is a
  server-side relationship and the server has never seen that file.
  `addByKeys` counts those and the message says how many were left behind,
  because "12 added" when three were dropped is the kind of small lie that makes
  people stop trusting the app.
- **Removing from an album deletes nothing**, and the wording in the dialogs says
  so. Deleting an album deletes no photos either. Freeing local space does not
  change membership at all — the server still holds the file.

## The device token at rest (§6, §13)

The token is a bearer credential for the whole library, so it is the one setting
that is encrypted: AES-256-GCM under a key generated in the Android Keystore,
which the app can use but never read out. Everything else — server address,
battery rules, the sync cursor — is stored as it reads, because encrypting a
Wi-Fi toggle buys nothing and hides what is actually sensitive.

This replaces `androidx.security.crypto`, which is deprecated upstream. §6 asks
for encrypted storage, not for that library, and `KeystoreCipher` is about eighty
lines — small enough to read in full, which matters more here than anywhere else
in the app. The key is deliberately **not** lock-screen bound: §10 runs the batch
at night on a locked phone with nobody there to authenticate.

Two decisions worth knowing about:

- **An unreadable token reads as signed out, not as a crash.** A wiped Keystore
  or a damaged value returns null and the user is asked for their password
  again, which is something they can act on. Throwing would be a crash on every
  cold start with no way out but clearing app data.
- **Signing out deletes the key, not just the value.** A stray copy of the old
  ciphertext is then unusable by anyone.

`LegacyPrefsMigration` moves phones that were already paired under the old
library across on first launch, so nobody is signed out of a library they
already have. It is the only code left touching the deprecated dependency and
can be deleted a release after v1.

## The backup engine (M3)

`BackupWorker` runs the loop from §10: scan → hash → one batched `/assets/check`
for up to 500 hashes → chunked upload → complete. It runs as a foreground
service so a batch survives the screen going off, and the notification carries
the file name and transfer rate §10.6 asks for.

Retries live at two levels. A chunk that fails transiently is retried three
times with exponential backoff plus jitter; a file that fails outright has its
`attemptCount` bumped and is retried on later runs until it hits six, after
which it shows up in the UI with its error rather than disappearing.
`RANGE_GAP` and `SESSION_RESET` are treated as instructions, not failures — the
server is the authority on what it holds.

`DISK_FULL` is neither. §16 makes the library "whatever fits on the disk", so a
full server is an ordinary ending: the run stops at the first refusal instead of
failing every remaining file, and the row is parked at `CHECKED` without
spending an attempt — otherwise a few runs against a full disk would strand the
photo at six attempts even after room is made. The timeline shows one line
across the top with the free space the server reported, and it clears itself the
moment a file uploads again.

Scheduling: a periodic job every six hours under the user's constraints, plus
"Back up now" which is expedited and ignores them once (§10.5).

### Verified on device

`UploadFailureTest` covers §17's five cases against a scripted MockWebServer:
connection dropped mid-chunk, server restart (session reset), hash mismatch,
duplicate file, and a full disk — twice for the last one, since the server can
refuse either when the session is opened or mid-chunk. It also covers a range
gap. All seven pass.

A full batch of 216 files was driven end to end, including a device reboot
mid-run: Room came back with the right state, no duplicates were created, and
once the app process was alive the queue drained to 216 verified.

## The gallery (M4)

`GalleryDao.observeTimeline()` is a UNION of the two tables: local rows first,
then server rows whose hash no local file has. A photo on both sides appears
once — the local copy wins because it loads from disk instantly. Server-only
rows are the "freed up space" and "photos from the other phone" case.

Delta sync pages `GET /assets?since=` until the server has nothing newer,
keeping tombstones so a deletion propagates. It also fills in the `remoteId`
values that §9's check endpoint cannot report, by matching hashes after a sync.

Three bugs the device found, each worth knowing about:

- **The grid key cannot be the hash.** A phone really does hold the same photo
  twice, and two cells sharing a key is a hard crash in LazyGrid. Keys are built
  from the row id.
- **Stacked gesture detectors starve each other.** `detectTransformGestures`,
  `detectTapGestures` and `detectVerticalDragGestures` each consume the initial
  down, so whichever Compose reaches first wins and the rest never fire — the
  symptom was a pager that would not page and a dismiss drag that registered as
  a tap. The viewer now decides intent (zoom / pan / dismiss / page) in one
  detector.
- **`popBackStack()` is not idempotent.** Deciding "dismiss" on every pointer
  event popped the timeline off too and left a blank screen. The decision now
  happens on release, behind a one-shot guard.

## Video (M5)

`PlayerFactory` builds every player the same way: a `CacheDataSource` in front
of `DefaultDataSource`, which handles `content://` locally and hands HTTP to the
same OkHttp client the API uses — so a server clip arrives with the device
token, since §13 serves no media without one. Nothing is transcoded anywhere;
the phone's decoder does the work, which is exactly why the server is allowed to
be a weak box.

The cache is a `SimpleCache` with LRU eviction, 512 MB by default. Combined with
the server's `Range` support it is what makes scrubbing backwards free.

The player is released in `onStop` and rebuilt with the saved position on the
way back, and a poster frame holds the screen until the decoder renders its
first frame. Gestures follow the M4 lesson — one detector decides between tap,
double-tap seek, horizontal scrub and vertical brightness/volume, because
stacked detectors starve each other.

Long-pressing a video cell in the grid plays it in place, muted, on a single
shared player for the whole timeline.

### Test video

`TestMedia.seedVideo()` encodes a real H.264 MP4 on the device with
MediaCodec + MediaMuxer, because the emulator ships no video and the dev machine
has no ffmpeg. Two things about that encoder are worth remembering: the frame
size passed to `queueInputBuffer` must be the input buffer's capacity, not
`width * height * 3 / 2` — rows are padded to the codec's alignment and the
smaller number truncates every frame; and chroma has to be written sample by
sample, because on a semi-planar layout a bulk row write stamps zeros over the
neighbouring plane.

## Polish (M6)

Three screens landed: **Backup status** (queue, failures with their errors and a
retry, storage used, server free space), **Settings** (network and battery
rules, skipped folders, cache, theme) and **Trash**.

Trash needed a server endpoint of its own — §9 defines delete and restore but no
way to list, and delta sync only carries tombstones, which are an id and a
timestamp. `GET /assets/trash` returns names, sizes and how long each item has
before the reaper takes it, so the screen can show a countdown instead of a
delete button. Nothing here destroys anything by hand: §2 says never lose a
file, and a countdown honours that better than a button does.

**Free up space** (§10.7) is the most dangerous code in the app, so it is the
most defensive. A row marked VERIFIED is treated as a memory, not a promise: the
server is asked again, right then, whether it still holds those exact hashes,
and anything it cannot vouch for is left alone and reported as withheld. Then
Android's own delete dialog asks the user. Afterwards only rows whose file has
genuinely gone are marked freed — if the user unticked something in the system
dialog, its row stays VERIFIED. `FreeUpSpaceTest` pins all three behaviours.

Selection mode (§12) feeds the same machine. Holding a photo starts a
selection — holding a **video** still previews it, which is §11's one flourish
and not worth trading away — and the picked photos become a scoped plan that
goes through the *same* server re-confirmation as the bulk action. That sharing
is deliberate: rule 2 is the only thing standing between a tap and somebody's
last copy, and a second implementation of it would eventually disagree with the
first. Server-only rows and anything not VERIFIED simply never come back from
the query, so a mixed selection needs no special case.

Haptics, Material You as an opt-in, reduce-motion support (`kadrSpring()`
collapses to a cut when the user has asked for less motion) and 48 dp minimum
touch targets are in `ui/Haptics.kt` and `ui/theme/Theme.kt`.

## Known gaps

- **Video playback is unproven on the emulator.** `c2.goldfish.h264.decoder`
  fails under ExoPlayer for both local and remote clips, with or without a
  surface attached. The clip itself is fine — `MediaMetadataRetriever` decodes a
  frame from it, the scanner reads 640×480 and the right duration — and the
  server half is verified: an authenticated `Range` request for the middle of an
  uploaded clip returns `206` with the exact byte count. What is left is
  playback on a real hardware decoder. §14 M5 is therefore **not signed off**.
- **A reboot does not yet resume the batch on its own.** `BootReceiver` fires
  and successfully enqueues the resume request (log-confirmed), and the periodic
  job survives the reboot with its constraints satisfied — but on the emulator
  the one-off resume never reaches JobScheduler, and nothing uploads until the
  app is opened. Three real bugs were fixed chasing this (a missing `goAsync()`
  so the enqueue was lost with the receiver's process, an `IllegalArgumentException`
  from putting a battery constraint on expedited work, and `ExistingWorkPolicy.KEEP`
  silently swallowing every later request); the remaining cause is not yet
  identified. §14's "survives a reboot mid-batch" is therefore **not signed off**.
  Worth retrying on a physical device before digging further.
- **Not verified by automation**: pinch-to-zoom between 2/3/5 columns and the
  shared-element animation itself. `adb shell input` cannot send multi-touch,
  and a transition is not something a screenshot proves. Both are wired and
  crash-free; they need a human with a device to sign off.
- The 800 ms cold start §15 asks for at 10,000 assets has not been **measured**
  on a device; the timeline is paged now (see below), but the number itself is
  still unverified.
- §12's right-edge fast scrubber is still not built.
- The album screens have **not been looked at** either, for the same reason as
  selection mode: an instrumentation run uninstalls the app afterwards. The data
  layer is tested; the list, the detail grid and the picker are unproven on a
  screen.
- Selection mode is built and its repository half is tested, but **nothing has
  looked at it**. The tick, the picked-cell inset and the selection bar have
  never been on a screen: an instrumentation run uninstalls the app afterwards,
  so a screenshot needs a signed-in build and a human. Same shelf as
  pinch-to-zoom and the shared-element transition.
- Server-side thumbnails need `ffmpeg` on the server. Without it `/thumb`
  answers 503 and server-only photos show an empty cell — local photos are
  unaffected because they render straight from the `content://` URI.
