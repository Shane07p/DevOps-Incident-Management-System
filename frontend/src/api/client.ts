import type { z } from 'zod'

const TOKEN_KEY = 'token'

/** The backend's one error shape; `errors` maps a field name to its message. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly errors: Record<string, string>

  constructor(status: number, message: string, errors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

type Options = { method?: string; body?: unknown }

/**
 * Calls the backend under /api, adds the Bearer token if there is one, and checks the response
 * against `schema` so a contract change fails loudly instead of rendering nonsense.
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
  const data: unknown = await response.json().catch(() => null)

  if (!response.ok) {
    const body = (data ?? {}) as { message?: unknown; errors?: unknown }
    const message = typeof body.message === 'string' ? body.message : `Request failed (${response.status})`
    const errors = body.errors && typeof body.errors === 'object' ? (body.errors as Record<string, string>) : {}
    throw new ApiRequestError(response.status, message, errors)
  }
  return schema.parse(data)
}

function readToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}
