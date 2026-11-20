import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setUnauthorizedHandler } from './client'
import { subscribeToEvents } from './events'
import { clearToken, readToken, writeToken } from './token'

const subscriptions: Array<() => void> = []
const openStream = () => new Response(new ReadableStream<Uint8Array>())

beforeEach(() => {
  vi.useFakeTimers()
  writeToken('stream-token')
})

afterEach(() => {
  subscriptions.splice(0).forEach(stop => stop())
  clearToken()
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('incident event transport', () => {
  it('does not connect, retry, or report an outage without a token', async () => {
    clearToken()
    const fetch = vi.fn()
    const connection = vi.fn()
    vi.stubGlobal('fetch', fetch)
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn(), onConnectionChange: connection }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetch).not.toHaveBeenCalled()
    expect(connection).not.toHaveBeenCalledWith(false)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('a 401 follows the shared sign-out handler and stops retrying', async () => {
    const signOut = vi.fn(() => clearToken())
    setUnauthorizedHandler(signOut)
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetch)
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn() }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(signOut).toHaveBeenCalledOnce()
    expect(readToken()).toBeNull()
    expect(fetch).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('a late 401 does not sign out a newer session', async () => {
    let respond!: (response: Response) => void
    const fetch = vi.fn(() => new Promise<Response>(resolve => { respond = resolve }))
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)
    vi.stubGlobal('fetch', fetch)
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn() }))
    writeToken('new-session')
    respond(new Response(null, { status: 401 }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(signOut).not.toHaveBeenCalled()
    expect(readToken()).toBe('new-session')
    expect(fetch).toHaveBeenCalledOnce()
  })

  it.each([403, 500])('HTTP %s is not an unreachable backend', async status => {
    const connection = vi.fn()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(null, { status }))))
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn(), onConnectionChange: connection }))
    await vi.advanceTimersByTimeAsync(7_000)
    expect(connection).not.toHaveBeenCalledWith(false)
  })

  it.each([502, 503, 504, 'network'] as const)('reports %s only after the first retry also fails', async status => {
    const connection = vi.fn()
    const fetch = status === 'network'
      ? vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
      : vi.fn(() => Promise.resolve(new Response(null, { status })))
    vi.stubGlobal('fetch', fetch)
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn(), onConnectionChange: connection }))
    await vi.advanceTimersByTimeAsync(0)
    expect(connection).not.toHaveBeenCalledWith(false)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(connection).toHaveBeenCalledWith(false)
  })

  it('resets backoff when a response opens even without an incident event', async () => {
    const closed = new Response(new ReadableStream<Uint8Array>({ start(controller) { controller.close() } }))
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(closed).mockImplementation(() => Promise.resolve(openStream()))
    vi.stubGlobal('fetch', fetch)
    subscriptions.push(subscribeToEvents({ onIncidentChanged: vi.fn() }))
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetch).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
})
