/**
 * Every response is either { data: ... } or { error: { code, message } } (§9).
 */

export function ok(reply, data, status = 200) {
  return reply.code(status).send({ data })
}

export function fail(reply, status, code, message, extra) {
  return reply.code(status).send({ error: { code, message, ...extra } })
}

export class ApiError extends Error {
  constructor(status, code, message, extra) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}
