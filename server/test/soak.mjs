/**
 * §15: "10,000 photos + 200 videos back up unattended overnight" and "server
 * process stays under 300 MB RSS during a bulk upload".
 *
 * Drives real uploads through the real API — check, session, chunk, complete —
 * and watches memory, latency and database growth as the library fills.
 *
 * Written in Node rather than as a shell script on purpose: the bash version
 * spawned six processes per asset and topped out at 1.5 assets/sec, which
 * measured Windows process creation rather than the server.
 *
 *   node test/soak.mjs                    # 10,000 assets
 *   COUNT=1000 CONCURRENCY=8 node test/soak.mjs
 */
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const COUNT = Number(process.env.COUNT || 10_000)
const CONCURRENCY = Number(process.env.CONCURRENCY || 6)
const PORT = Number(process.env.PORT || 8790)
const RSS_BUDGET_MB = 300
const ASSET_BYTES = 3 * 1024

const here = path.dirname(fileURLToPath(import.meta.url))
const serverRoot = path.resolve(here, '..')
const dataDir = path.join(serverRoot, '.soak-data')
const base = `http://127.0.0.1:${PORT}/api/v1`

let server
let token
let peakRss = 0

const ms = () => Number(process.hrtime.bigint() / 1_000_000n)

async function main() {
  await fs.rm(dataDir, { recursive: true, force: true })
  await startServer()
  await pair()

  console.log(`\x1b[1mSoak: ${COUNT.toLocaleString()} assets, concurrency ${CONCURRENCY}\x1b[0m\n`)

  const started = ms()
  let done = 0

  // A fixed pool rather than Promise.all over 10k: the point is steady
  // pressure, not 10,000 sockets at once.
  const workers = Array.from({ length: CONCURRENCY }, async () => {
    while (true) {
      const index = done < COUNT ? done++ : -1
      if (index < 0) break
      await uploadOne(index)

      const completed = index + 1
      if (completed % 500 === 0) {
        const health = await getJson('/health')
        peakRss = Math.max(peakRss, health.rssBytes)
        const elapsed = ((ms() - started) / 1000).toFixed(0)
        process.stdout.write(
          `  ${String(completed).padStart(6)} / ${COUNT}   ` +
            `rss ${String(Math.round(health.rssBytes / 1048576)).padStart(4)} MB   ` +
            `${elapsed}s elapsed\n`,
        )
      }
    }
  })

  await Promise.all(workers)
  const totalSeconds = (ms() - started) / 1000

  console.log(`\n\x1b[1mLibrary behaviour at ${COUNT.toLocaleString()} assets\x1b[0m`)

  const health = await getJson('/health')
  peakRss = Math.max(peakRss, health.rssBytes)

  // The dedupe probe a phone fires on every batch (§10.3).
  const probeHashes = Array.from({ length: 500 }, (_, i) =>
    crypto.createHash('sha256').update(`probe-${i}`).digest('hex'),
  )
  const checkStart = ms()
  await postJson('/assets/check', { hashes: probeHashes })
  const checkMs = ms() - checkStart

  // A dedupe probe where every hash IS present — the expensive direction.
  const knownHashes = Array.from({ length: 500 }, (_, i) => hashFor(i))
  const knownStart = ms()
  const knownResult = await postJson('/assets/check', { hashes: knownHashes })
  const knownMs = ms() - knownStart

  // Paging the whole library, the way a fresh install syncs it.
  const syncStart = ms()
  let cursor = 0
  let pages = 0
  let seen = 0
  let firstPageMs = 0
  while (true) {
    const pageStart = ms()
    const page = await getJson(`/assets?since=${cursor}&limit=500`)
    if (pages === 0) firstPageMs = ms() - pageStart
    seen += page.assets.length
    cursor = page.nextCursor
    pages++
    if (!page.hasMore) break
  }
  const syncMs = ms() - syncStart

  const report = [
    ['assets stored', health.assetCount.toLocaleString()],
    [
      'upload wall time',
      `${totalSeconds.toFixed(0)}s (${(COUNT / Math.max(totalSeconds, 1)).toFixed(0)} assets/sec)`,
    ],
    ['peak RSS', `${Math.round(peakRss / 1048576)} MB (budget ${RSS_BUDGET_MB} MB)`],
    ['RSS at rest', `${Math.round(health.rssBytes / 1048576)} MB`],
    ['database', `${(health.dbSizeBytes / 1048576).toFixed(1)} MB`],
    ['free disk left', `${(health.freeDiskBytes / 1073741824).toFixed(1)} GB`],
    ['500 unknown hashes', `${checkMs}ms`],
    ['500 known hashes', `${knownMs}ms (${500 - knownResult.missing.length} already held)`],
    ['first sync page', `${firstPageMs}ms`],
    ['full delta sync', `${syncMs}ms over ${pages} pages, ${seen.toLocaleString()} rows`],
  ]
  for (const [label, value] of report) {
    console.log(`  ${label.padEnd(24)} ${value}`)
  }

  console.log('\n───────────────────────────────')
  let failed = 0

  if (health.assetCount !== COUNT) {
    console.log(`\x1b[31m✗\x1b[0m stored ${health.assetCount} of ${COUNT} assets`)
    failed = 1
  } else {
    console.log('\x1b[32m✓\x1b[0m every asset landed')
  }

  if (seen < COUNT) {
    console.log(`\x1b[31m✗\x1b[0m delta sync returned ${seen} of ${COUNT} rows`)
    failed = 1
  } else {
    console.log('\x1b[32m✓\x1b[0m delta sync paged the whole library')
  }

  const peakMb = Math.round(peakRss / 1048576)
  if (peakMb > RSS_BUDGET_MB) {
    console.log(`\x1b[31m✗\x1b[0m peak RSS ${peakMb} MB exceeds the ${RSS_BUDGET_MB} MB budget`)
    failed = 1
  } else {
    console.log(`\x1b[32m✓\x1b[0m stayed inside the ${RSS_BUDGET_MB} MB budget`)
  }

  console.log()
  process.exitCode = failed
}

// ─── One asset, the full round trip ─────────────────────────────────────────

function hashFor(index) {
  return crypto.createHash('sha256').update(bodyFor(index)).digest('hex')
}

/**
 * Deterministic per index so a re-run builds the same library, and unique per
 * index so nothing is deduplicated away. Hash-chained filler is incompressible
 * enough to be honest about disk use without needing a random source.
 */
function bodyFor(index) {
  const seed = Buffer.from(`kadr-soak-asset-${index}\n`)
  const padding = Buffer.alloc(ASSET_BYTES - seed.length)
  let block = crypto.createHash('sha256').update(seed).digest()
  for (let cursor = 0; cursor < padding.length; cursor += block.length) {
    block.copy(padding, cursor)
    block = crypto.createHash('sha256').update(block).digest()
  }
  return Buffer.concat([seed, padding])
}

async function uploadOne(index) {
  const body = bodyFor(index)
  const sha256 = crypto.createHash('sha256').update(body).digest('hex')

  await postJson('/assets/check', { hashes: [sha256] })

  const session = await postJson('/uploads', {
    sha256,
    sizeBytes: body.length,
    filename: `soak_${index}.jpg`,
    mimeType: 'image/jpeg',
    capturedAt: 1_700_000_000_000 + index * 60_000,
    width: 4032,
    height: 3024,
  })

  if (session.alreadyExists) return

  const response = await fetch(`${base}/uploads/${session.uploadId}`, {
    method: 'PATCH',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/octet-stream',
      'content-range': `bytes 0-${body.length - 1}/${body.length}`,
    },
    body,
  })
  if (!response.ok) throw new Error(`chunk failed: ${response.status} ${await response.text()}`)

  await postJson(`/uploads/${session.uploadId}/complete`, undefined)
}

// ─── Plumbing ───────────────────────────────────────────────────────────────

async function getJson(pathname) {
  const response = await fetch(`${base}${pathname}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(`${pathname}: ${JSON.stringify(payload)}`)
  return payload.data
}

async function postJson(pathname, json) {
  const response = await fetch(`${base}${pathname}`, {
    method: 'POST',
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(json === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: json === undefined ? undefined : JSON.stringify(json),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(`${pathname}: ${JSON.stringify(payload)}`)
  return payload.data
}

async function startServer() {
  server = spawn(process.execPath, ['src/index.js'], {
    cwd: serverRoot,
    env: { ...process.env, KADR_PORT: String(PORT), KADR_DATA_DIR: dataDir },
    stdio: 'ignore',
  })

  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      await getJson('/health')
      return
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
  }
  throw new Error('server did not come up')
}

async function pair() {
  const { code } = await postJson('/auth/pair-code', undefined)
  const paired = await postJson('/auth/pair', { code, deviceName: 'Soak' })
  token = paired.token
}

try {
  await main()
} finally {
  server?.kill()
}
