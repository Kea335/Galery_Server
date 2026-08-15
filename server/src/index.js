import { buildApp } from './app.js'
import { generatePairCode } from './auth.js'
import { config } from './config.js'
import { openDb } from './db.js'
import { reap } from './reaper.js'
import { ensureDirs } from './storage.js'

const db = openDb()
await ensureDirs()

const app = await buildApp({ db })

const reaperTimer = setInterval(() => {
  reap(db, app.log).catch((err) => app.log.error({ err }, 'reaper failed'))
}, config.reaperIntervalMs)
reaperTimer.unref()

await app.listen({ host: config.host, port: config.port })

// A fresh code every boot, valid for 5 minutes. Owner reads it off the console
// (or the local pairing page, once that exists) and types it into the phone.
const { code } = generatePairCode(db)
app.log.info(
  { dataDir: config.dataDir },
  `Kadr ${config.version} ready — pairing code ${code} (valid 5 min)`,
)

reap(db, app.log).catch((err) => app.log.error({ err }, 'startup reaper failed'))

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, async () => {
    app.log.info(`${signal} received, shutting down`)
    clearInterval(reaperTimer)
    try {
      await app.close()
      db.close()
    } finally {
      process.exit(0)
    }
  })
}
