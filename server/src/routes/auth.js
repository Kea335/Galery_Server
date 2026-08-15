import { consumePairCode, createDevice, generatePairCode } from '../auth.js'
import { fail, ok } from '../errors.js'

export default async function authRoutes(app, { db, pairLimiter, requireDevice }) {
  app.post('/auth/pair', async (request, reply) => {
    const ip = request.ip

    const gate = pairLimiter.check(ip)
    if (!gate.allowed) {
      reply.header('Retry-After', Math.ceil(gate.retryAfterMs / 1000))
      return fail(
        reply,
        429,
        'PAIR_LOCKED',
        `Too many failed attempts. Try again in ${Math.ceil(gate.retryAfterMs / 60000)} minutes.`,
      )
    }

    const body = request.body ?? {}
    const code = typeof body.code === 'string' ? body.code.trim() : ''
    const deviceName =
      typeof body.deviceName === 'string' && body.deviceName.trim()
        ? body.deviceName.trim().slice(0, 100)
        : 'Unnamed device'

    if (!/^\d{6}$/.test(code)) {
      pairLimiter.fail(ip)
      return fail(reply, 400, 'INVALID_CODE', 'Pairing code must be 6 digits.')
    }

    if (!consumePairCode(db, code)) {
      pairLimiter.fail(ip)
      return fail(reply, 401, 'INVALID_CODE', 'Pairing code is wrong, expired or already used.')
    }

    pairLimiter.succeed(ip)
    const { deviceId, token } = createDevice(db, deviceName)
    app.log.info({ deviceId, deviceName }, 'device paired')

    return ok(reply, { deviceId, token }, 201)
  })

  app.post('/auth/revoke', { preHandler: requireDevice }, async (request, reply) => {
    db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(request.device.id)
    app.log.info({ deviceId: request.device.id }, 'device revoked its own token')
    return ok(reply, { revoked: true })
  })

  /**
   * Generating a pairing code is an owner action, not a client one, so it is
   * bound to loopback. The phone never calls this — a human reads the code off
   * the server's console or its local page.
   */
  app.post('/auth/pair-code', async (request, reply) => {
    if (!isLoopback(request.ip)) {
      return fail(reply, 403, 'FORBIDDEN', 'Pairing codes can only be generated locally.')
    }
    const { code, expiresAt } = generatePairCode(db)
    return ok(reply, { code, expiresAt })
  })
}

function isLoopback(ip) {
  return ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1'
}
