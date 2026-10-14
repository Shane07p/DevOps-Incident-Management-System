import { vi } from 'vitest'

type Route = { status?: number; body: unknown }
type Handler = Route | ((init: RequestInit | undefined) => Route)

/** Stubs fetch with responses keyed by "METHOD /api/path"; returns the mock so tests can inspect calls. */
export function mockFetch(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`
    const handler = routes[key]
    if (!handler) return new Response(JSON.stringify({ status: 404, message: `No mock for ${key}` }), { status: 404 })
    const route = typeof handler === 'function' ? handler(init) : handler
    return new Response(JSON.stringify(route.body), {
      status: route.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export const payments = {
  id: 1,
  name: 'Payments',
  description: 'Card and wallet payments',
  owner: { id: 1, displayName: 'Dev Admin' },
  criticality: 'CRITICAL',
  targets: {
    SEV1: { responseMinutes: 15, resolutionMinutes: 240 },
    SEV2: { responseMinutes: 60, resolutionMinutes: 480 },
    SEV3: { responseMinutes: 240, resolutionMinutes: 2880 },
    SEV4: { responseMinutes: 1440, resolutionMinutes: 10080 },
  },
  createdAt: '2026-10-12T10:00:00Z',
  updatedAt: '2026-10-12T10:00:00Z',
}

export const defaultTargets = {
  SEV1: { responseMinutes: 15, resolutionMinutes: null },
  SEV2: { responseMinutes: 60, resolutionMinutes: null },
  SEV3: { responseMinutes: 240, resolutionMinutes: null },
  SEV4: { responseMinutes: 1440, resolutionMinutes: null },
}

export const users = [
  { id: 1, username: 'admin', displayName: 'Dev Admin', role: 'ADMIN' },
  { id: 2, username: 'engineer', displayName: 'Dev Engineer', role: 'ENGINEER' },
]
