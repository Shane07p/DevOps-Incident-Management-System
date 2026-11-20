import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { LiveUpdatesProvider, useLiveUpdates } from './useLiveUpdates'

const { user } = vi.hoisted(() => ({ user: { id: 7, role: 'ENGINEER' } }))
vi.mock('@/auth/AuthProvider', () => ({ useAuth: () => ({ user }) }))

afterEach(() => {
  cleanup()
  clearToken()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('a first-retry reconnect refetches the list once without flashing an outage', async () => {
  vi.useFakeTimers()
  writeToken('token')
  let stream!: ReadableStreamDefaultController<Uint8Array>
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller } })))
    .mockResolvedValueOnce(new Response(new ReadableStream<Uint8Array>()))
  vi.stubGlobal('fetch', fetch)
  const list = vi.fn().mockResolvedValue([])
  const states: boolean[] = []
  function Probe() {
    useQuery({ queryKey: ['incidents'], queryFn: list })
    states.push(useLiveUpdates().isBackendUnreachable)
    return null
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><LiveUpdatesProvider><Probe /></LiveUpdatesProvider></QueryClientProvider>)
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
  expect(list).toHaveBeenCalledOnce()
  await act(async () => { stream.error(new TypeError('dropped connection')); await vi.advanceTimersByTimeAsync(0) })
  expect(states).not.toContain(true)
  await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(list).toHaveBeenCalledTimes(2)
  expect(states).not.toContain(true)
  cleanup()
  client.clear()
})
