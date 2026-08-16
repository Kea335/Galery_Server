import { createDevice } from '../auth.js'
import { fail, ok } from '../errors.js'
import { authenticate, userCount } from '../users.js'

export default async function authRoutes(app, { db, loginLimiter, requireDevice }) {
  /**
   * Sign in. Anyone who installs the app and gets the username and password
   * right sees the same library — that is the point.
   *
   * The password is exchanged for a device token here and never sent again.
   */
  app.post('/auth/login', async (request, reply) => {
    const ip = request.ip

    const gate = loginLimiter.check(ip)
    if (!gate.allowed) {
      reply.header('Retry-After', Math.ceil(gate.retryAfterMs / 1000))
      return fail(
        reply,
        429,
        'LOGIN_LOCKED',
        `Too many failed attempts. Try again in ${Math.ceil(gate.retryAfterMs / 60000)} minutes.`,
      )
    }

    const body = request.body ?? {}
    const username = typeof body.username === 'string' ? body.username : ''
    const password = typeof body.password === 'string' ? body.password : ''
    const deviceName =
      typeof body.deviceName === 'string' && body.deviceName.trim()
        ? body.deviceName.trim().slice(0, 100)
        : 'Unnamed device'

    if (!username || !password) {
      loginLimiter.fail(ip)
      return fail(reply, 400, 'BAD_CREDENTIALS', 'Username and password are required.')
    }

    if (userCount(db) === 0) {
      return fail(
        reply,
        503,
        'NO_ACCOUNT',
        'This server has no account yet. Create one with: node src/cli.js user add <name>',
      )
    }

    const user = await authenticate(db, username, password)
    if (!user) {
      loginLimiter.fail(ip)
      // Deliberately vague: which half was wrong is not the client's business.
      return fail(reply, 401, 'BAD_CREDENTIALS', 'Wrong username or password.')
    }

    loginLimiter.succeed(ip)
    const { deviceId, token } = createDevice(db, user.id, deviceName)
    app.log.info({ deviceId, deviceName, username: user.username }, 'device signed in')

    return ok(reply, { deviceId, token, username: user.username }, 201)
  })

  app.post('/auth/revoke', { preHandler: requireDevice }, async (request, reply) => {
    db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(request.device.id)
    app.log.info({ deviceId: request.device.id }, 'device revoked its own token')
    return ok(reply, { revoked: true })
  })

  /** Which phones are signed in — surfaced so a lost one can be cut off. */
  app.get('/auth/devices', { preHandler: requireDevice }, async (request, reply) => {
    const devices = db
      .prepare(
        `SELECT id, name, created_at, last_seen_at, revoked
         FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC`,
      )
      .all(request.device.user_id)

    return ok(reply, {
      devices: devices.map((device) => ({
        id: device.id,
        name: device.name,
        createdAt: device.created_at,
        lastSeenAt: device.last_seen_at,
        revoked: Boolean(device.revoked),
        current: device.id === request.device.id,
      })),
    })
  })
}
