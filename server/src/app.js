import Fastify from 'fastify'
import { createAuthHook, createLoginLimiter } from './auth.js'
import albumRoutes from './routes/albums.js'
import assetRoutes from './routes/assets.js'
import authRoutes from './routes/auth.js'
import healthRoutes from './routes/health.js'
import uploadRoutes from './routes/uploads.js'

export async function buildApp({ db, logger = true } = {}) {
  const app = Fastify({
    logger,
    // Chunk bodies are streamed straight to disk, never buffered — this limit
    // only guards the small JSON payloads.
    bodyLimit: 1024 * 1024,
  })

  // Raw passthrough for upload chunks: request.body is the stream itself, so a
  // 2 GB video never lands in RAM (§4, 300 MB RSS budget).
  const rawStream = (_request, payload, done) => done(null, payload)
  app.addContentTypeParser('application/octet-stream', rawStream)
  app.addContentTypeParser('*', rawStream)

  const requireDevice = createAuthHook(db)
  const loginLimiter = createLoginLimiter()
  const deps = { db, requireDevice, loginLimiter }

  await app.register(healthRoutes, { prefix: '/api/v1', ...deps })
  await app.register(authRoutes, { prefix: '/api/v1', ...deps })
  await app.register(uploadRoutes, { prefix: '/api/v1', ...deps })
  await app.register(assetRoutes, { prefix: '/api/v1', ...deps })
  await app.register(albumRoutes, { prefix: '/api/v1', ...deps })

  app.setNotFoundHandler((request, reply) => {
    reply.code(404).send({
      error: { code: 'NOT_FOUND', message: `No route for ${request.method} ${request.url}.` },
    })
  })

  app.setErrorHandler((err, request, reply) => {
    const status = Number.isInteger(err.statusCode) && err.statusCode >= 400 ? err.statusCode : 500
    if (status >= 500) request.log.error({ err }, 'request failed')

    if (err.code === 'ENOSPC') {
      return reply
        .code(507)
        .send({ error: { code: 'DISK_FULL', message: 'The server has run out of disk space.' } })
    }

    reply.code(status).send({
      error: {
        code: err.code && status < 500 ? String(err.code) : 'INTERNAL',
        message: status < 500 ? err.message : 'Internal server error.',
      },
    })
  })

  return app
}
