import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { mockFetch } from '@/test/fetchMock'
import { ApiRequestError, apiFetch, setUnauthorizedHandler } from './client'
import { loginResponseSchema, noContentSchema } from './schemas'
import { clearToken, writeToken } from './token'

afterEach(() => {
  clearToken()
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
})

describe('apiFetch', () => {
  it('sends the saved token as a Bearer header', async () => {
    writeToken('abc')
    const fetchMock = mockFetch({ 'GET /api/ping': { body: { ok: true } } })

    await apiFetch('/ping', z.object({ ok: z.boolean() }))

    const headers = fetchMock.mock.calls[0][1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer abc')
  })

  it('turns an error response into ApiRequestError with the field errors', async () => {
    mockFetch({
      'POST /api/users': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { email: 'Email is taken' } },
      },
    })

    const error = await apiFetch('/users', z.unknown(), { method: 'POST', body: {} }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiRequestError)
    expect(error).toMatchObject({ status: 400, message: 'Validation failed', errors: { email: 'Email is taken' } })
  })

  it('rejects a response that does not match the schema', async () => {
    mockFetch({ 'GET /api/ping': { body: { ok: 'yes' } } })

    await expect(apiFetch('/ping', z.object({ ok: z.boolean() }))).rejects.toThrow()
  })

  it('accepts an Engineer in the login response', async () => {
    mockFetch({
      'POST /api/auth/login': {
        body: {
          token: 't',
          expiresAt: '2026-10-14T07:06:57.990535700Z',
          user: { id: 2, email: 'engineer@settle.local', displayName: 'Dev Engineer', role: 'ENGINEER' },
          org: { id: 1, name: 'Dev org', slug: 'dev-org' },
        },
      },
    })

    const response = await apiFetch('/auth/login', loginResponseSchema, { method: 'POST', body: {} })

    expect(response.user.role).toBe('ENGINEER')
  })

  it('resolves a 204 to undefined', async () => {
    mockFetch({ 'POST /api/auth/logout': { status: 204, body: null } })

    await expect(apiFetch('/auth/logout', noContentSchema, { method: 'POST' })).resolves.toBeUndefined()
  })

  it('calls the unauthorized handler when a sent token is refused', async () => {
    writeToken('expired')
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch({ 'GET /api/auth/me': { status: 401, body: {} } })

    await apiFetch('/auth/me', z.unknown()).catch(() => undefined)

    expect(handler).toHaveBeenCalledOnce()
  })

  it('does not call the unauthorized handler for a wrong password', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch({ 'POST /api/auth/login': { status: 401, body: { status: 401, message: 'Invalid email or password' } } })

    await apiFetch('/auth/login', z.unknown(), { method: 'POST', body: {} }).catch(() => undefined)

    expect(handler).not.toHaveBeenCalled()
  })
})
