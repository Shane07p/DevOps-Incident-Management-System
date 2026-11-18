import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Heartbeat } from '@/api/heartbeats'
import { mockFetch } from '@/test/fetchMock'
import { HeartbeatsPage } from './HeartbeatsPage'
import { heartbeatStatus } from './heartbeats/heartbeatStatus'

const NOW = Date.parse('2026-11-13T10:00:00Z')

const base: Heartbeat = {
  id: 1, name: 'Order sync', serviceId: 1, serviceName: 'Payments', intervalSeconds: 300, graceSeconds: 120,
  enabled: true, lastPingAt: '2026-11-13T09:57:00Z', nextDueAt: '2026-11-13T10:02:00Z', lateAfter: '2026-11-13T10:04:00Z',
  outageStartedAt: null, activeAlertId: null,
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('heartbeatStatus', () => {
  it('reads on time, late, missed, waiting, and paused from the fields alone', () => {
    expect(heartbeatStatus(base, NOW).label).toBe('On time')
    expect(heartbeatStatus(base, Date.parse('2026-11-13T10:03:00Z')).label).toBe('Late')
    expect(heartbeatStatus(base, Date.parse('2026-11-13T10:05:00Z')).label).toBe('Missed')
    expect(heartbeatStatus({ ...base, outageStartedAt: '2026-11-13T09:00:00Z' }, NOW).label).toBe('Missed')
    expect(heartbeatStatus({ ...base, lastPingAt: null, nextDueAt: null, lateAfter: null }, NOW).label).toBe('Waiting for the first ping')
    expect(heartbeatStatus({ ...base, enabled: false }, NOW).label).toBe('Paused')
  })
})

describe('HeartbeatsPage', () => {
  it('lists the real heartbeats, and names a missed one at the top', async () => {
    mockFetch({
      'GET /api/heartbeats': {
        body: [
          base,
          { ...base, id: 2, name: 'Nightly export', outageStartedAt: '2026-11-13T02:10:00Z', activeAlertId: 9 },
        ],
      },
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <HeartbeatsPage />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('cell', { name: 'Order sync' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Nightly export')
    expect(screen.getAllByText('Every 5 min')).toHaveLength(2)
    expect(screen.queryByText(/sample|demonstration/i)).not.toBeInTheDocument()
  })
})
