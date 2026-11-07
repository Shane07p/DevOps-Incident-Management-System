import type { z } from 'zod'
import { readToken } from './token'

/** The backend's one error shape; `errors` maps a field name to its message. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly errors: Record<string, string>

  constructor(status: number, message: string, errors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.errors = errors
  }
}

type Options = { method?: string; body?: unknown }

let onUnauthorized: (() => void) | null = null

/** The auth provider registers this, so a rejected token logs the user out wherever it happens. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

/**
 * Calls the backend under /api, adds the Bearer token if there is one, and checks the response
 * against `schema` so a contract change fails loudly instead of rendering nonsense. A 204 response
 * has no body and resolves to undefined.
 */
export async function apiFetch<T>(path: string, schema: z.ZodType<T>, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = readToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(`/api${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })

  // A 401 on a request that carried a token means the token expired or was revoked. A 401
  // without one is a wrong password on the login form, which the form shows itself. A late 401 for
  // a token that has since been replaced (another sign-in) says nothing about the new one.
  if (response.status === 401 && token && readToken() === token) onUnauthorized?.()

  const data: unknown = response.status === 204 ? undefined : await response.json().catch(() => null)

  if (!response.ok) {
    const body = (data ?? {}) as { message?: unknown; errors?: unknown }
    const message = typeof body.message === 'string' ? body.message : `Request failed (${response.status})`
    const errors = body.errors && typeof body.errors === 'object' ? (body.errors as Record<string, string>) : {}
    throw new ApiRequestError(response.status, message, errors)
  }
  return schema.parse(data)
}
