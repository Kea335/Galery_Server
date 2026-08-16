import { buildApp } from './app.js'
import { config } from './config.js'
import { openDb } from './db.js'
import { reap } from './reaper.js'
import { ensureDirs } from './storage.js'
import { userCount } from './users.js'

const db = openDb()
await ensureDirs()

const app = await buildApp({ db })

const reaperTimer = setInterval(() => {
  reap(db, app.log).catch((err) => app.log.error({ err }, 'reaper failed'))
}, config.reaperIntervalMs)
reaperTimer.unref()

await app.listen({ host: config.host, port: config.port })

app.log.info({ dataDir: config.dataDir }, `Kadr ${config.version} ready`)

// A server nobody can sign in to is not much use, and the reason is not
// obvious from a 401 on the phone. Say it here, where the owner is looking.
if (userCount(db) === 0) {
  app.log.warn(
    'No account exists yet. Create one before signing in from the app:\n' +
      '    node src/cli.js user add <username>',
  )
}

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
