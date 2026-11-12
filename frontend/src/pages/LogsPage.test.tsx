/**
 * LogsPage.test.tsx
 * Tests for Issue #163 — Logs Explorer (US-28 / TC-FR-26.1b).
 *
 * The API from #156: GET /api/telemetry/logs?serviceId=&level=&text=&from=&to=&limit= answers a list of
 * LogEntryResponse. It reads one service at a time and has no field filter, so a clicked JSON field
 * narrows the lines already loaded.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch } from '@/test/fetchMock'

// ---------------------------------------------------------------------------
// Shared fixtures in the shape of LogEntryResponse
// ---------------------------------------------------------------------------

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

const services = [
  { id: 1, name: 'Payments' },
  { id: 2, name: 'Checkout' },
]

const sampleLogEntries = [
  {
    id: '1761642000000000000:0',
    timestamp: '2026-10-28T09:00:00Z',
    serviceId: 1,
    serviceName: 'Payments',
    level: 'ERROR',
    message: 'payment.failed',
    attributes: { order_id: 'ord-123', error: 'gateway timeout' },
  },
  {
    id: '1761641700000000000:1',
    timestamp: '2026-10-28T08:55:00Z',
    serviceId: 1,
    serviceName: 'Payments',
    level: 'INFO',
    message: 'Payment processed successfully for user 456',
    attributes: {},
  },
  {
    id: '1761641400000000000:2',
    timestamp: '2026-10-28T08:50:00Z',
    serviceId: 1,
    serviceName: 'Payments',
    level: 'WARN',
    // A line that is not JSON comes back whole, with no attributes.
    message: '{"broken json: true',
    attributes: {},
  },
]

const sampleLogsResponse = sampleLogEntries

/** The query each logs request carried. */
function logRequests(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls
    .map(([input]) => String(input))
    .filter((url) => url.startsWith('/api/telemetry/logs'))
    .map((url) => new URL(url, 'http://localhost').searchParams)
}

type ExtraRoutes = Parameters<typeof mockFetch>[0]

function renderLogs(path = '/logs', extraRoutes: ExtraRoutes = {}) {
  writeToken('test-token')
  const defaultRoutes: Record<string, any> = {
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/services': { body: services },
    'GET /api/telemetry/logs': { body: sampleLogsResponse },
    ...extraRoutes,
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const fullKey = `${method} ${String(input)}`
    const unencodedKey = `${method} ${decodeURIComponent(String(input))}`
    const pathOnly = String(input).split('?')[0]
    const pathKey = `${method} ${pathOnly}`

    // Exact match first, then decoded match, then fallback to path without query string
    const handler = defaultRoutes[fullKey] ?? defaultRoutes[unencodedKey] ?? defaultRoutes[pathKey]
    if (!handler) {
      return new Response(JSON.stringify({ status: 404, message: `No mock for ${fullKey}` }), { status: 404 })
    }
    const route = typeof handler === 'function' ? handler(init) : handler
    if (route.status === 204) return new Response(null, { status: 204 })
    return new Response(JSON.stringify(route.body), {
      status: route.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)

  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <TooltipProvider>
            <RouterProvider router={router} />
          </TooltipProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )

  return { fetchMock, router, queryClient }
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

// ---------------------------------------------------------------------------
// 1. Rendering
// ---------------------------------------------------------------------------
describe('LogsPage — rendering', () => {
  it('renders page heading, filters, and live tail button', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    expect(await screen.findByRole('heading', { name: 'Logs Explorer' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Level' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Time range' })).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: 'Search logs' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Live tail' })).toBeInTheDocument()

    // Logs rendered
    expect(await screen.findByText('Payment processed successfully for user 456')).toBeInTheDocument()
    expect(screen.getByText('3 log entries')).toBeInTheDocument()
  })

  it('reads the first service until one is chosen, since the server reads one service at a time', async () => {
    const { fetchMock } = renderLogs('/logs')

    expect(await screen.findByText('3 log entries')).toBeInTheDocument()
    expect(logRequests(fetchMock)[0].get('serviceId')).toBe('1')
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveTextContent('Payments')
  })

  it('offers only the levels the server filters on', async () => {
    renderLogs('/logs')
    await screen.findByText('3 log entries')

    await userEvent.click(screen.getByRole('combobox', { name: 'Level' }))
    expect(await screen.findByRole('option', { name: 'ERROR' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'FATAL' })).not.toBeInTheDocument()
  })

  it('says so when the org has no services yet', async () => {
    const { fetchMock } = renderLogs('/logs', { 'GET /api/services': { body: [] } })

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(logRequests(fetchMock)).toHaveLength(0)
  })

  it('does not show "All time" option in the time range selector', async () => {
    renderLogs('/logs')
    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Time range' }))
    expect(screen.queryByRole('option', { name: 'All time' })).not.toBeInTheDocument()
    // Max range is 24 hours
    expect(await screen.findByRole('option', { name: 'Last 24 hours' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 2. Service filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — service filter', () => {
  it('applies service filter and queries logs for that service', async () => {
    const { router, fetchMock } = renderLogs('/logs')

    await screen.findByText('3 log entries')

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Checkout' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=2')
      expect(logRequests(fetchMock).at(-1)?.get('serviceId')).toBe('2')
    })
  })
})

// ---------------------------------------------------------------------------
// 3. Level filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — level filter', () => {
  it('applies level filter and updates query', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Level' }))
    await userEvent.click(await screen.findByRole('option', { name: 'ERROR' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('level=ERROR')
    })
  })
})

// ---------------------------------------------------------------------------
// 4. Text filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — text search filter', () => {
  it('debounces and applies text search filter', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    const input = screen.getByRole('searchbox', { name: 'Search logs' })
    await userEvent.type(input, 'timeout')

    await waitFor(
      () => {
        expect(router.state.location.search).toContain('text=timeout')
      },
      { timeout: 1000 },
    )
  })
})

// ---------------------------------------------------------------------------
// 5. Time-range filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — time-range filter', () => {
  it('applies chosen time-range in URL parameters', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Time range' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Last 15 minutes' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('timeRange=15m')
    })
  })

  it('respects explicit from and to query params', async () => {
    renderLogs('/logs?from=2026-10-28T08:00:00Z&to=2026-10-28T09:00:00Z', {
      'GET /api/telemetry/logs?serviceId=1&from=2026-10-28T08:00:00Z&to=2026-10-28T09:00:00Z&limit=100': { body: [sampleLogEntries[0]] },
    })

    expect(await screen.findByText('1 log entries')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 6. Combined filters build correct request
// ---------------------------------------------------------------------------
describe('LogsPage — combined filters', () => {
  it('combines service, level, and text filters together', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Checkout' }))

    await userEvent.click(screen.getByRole('combobox', { name: 'Level' }))
    await userEvent.click(await screen.findByRole('option', { name: 'ERROR' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=2')
      expect(router.state.location.search).toContain('level=ERROR')
    })
  })
})

// ---------------------------------------------------------------------------
// 7. A clicked JSON field narrows the lines loaded (TC-FR-26.1b)
// ---------------------------------------------------------------------------
describe('LogsPage — clickable JSON fields (TC-FR-26.1b)', () => {
  it('clicking a JSON field keeps only the lines with that value and shows a filter chip', async () => {
    const { fetchMock } = renderLogs('/logs')

    await userEvent.click(await screen.findByRole('button', { name: 'Filter by order_id: ord-123' }))

    expect(await screen.findByText(/Field:/)).toBeInTheDocument()
    expect(screen.getByText('1 of 3 log entries')).toBeInTheDocument()
    expect(screen.getAllByTestId('log-entry')).toHaveLength(1)
    expect(screen.queryByText('Payment processed successfully for user 456')).not.toBeInTheDocument()
    // The server has no field filter: nothing new is asked for, and the value never becomes text.
    expect(logRequests(fetchMock)).toHaveLength(1)
    expect(logRequests(fetchMock)[0].has('field')).toBe(false)
    expect(logRequests(fetchMock)[0].has('text')).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// 8. Clearing a filter updates the request
// ---------------------------------------------------------------------------
describe('LogsPage — clearing filters', () => {
  it('clearing active field filter removes it and updates query', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    const orderBtn = await screen.findByRole('button', { name: 'Filter by order_id: ord-123' })
    await userEvent.click(orderBtn)

    // Clear the field filter
    const clearFieldBtn = await screen.findByRole('button', { name: 'Clear field filter' })
    await userEvent.click(clearFieldBtn)

    await waitFor(() => {
      expect(screen.queryByText(/Field:/)).not.toBeInTheDocument()
    })
  })

  it('clicking Clear filters button resets all parameters', async () => {
    const { router } = renderLogs('/logs?serviceId=1&level=ERROR', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    expect(await screen.findByRole('button', { name: 'Clear filters' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    await waitFor(() => {
      expect(router.state.location.search).toBe('')
    })
  })
})

// ---------------------------------------------------------------------------
// 9. Live tail starts polling at ≥ 5-second intervals
// ---------------------------------------------------------------------------
describe('LogsPage — live tail', () => {
  it('starts polling when live tail is enabled', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    const liveTailBtn = await screen.findByRole('button', { name: 'Live tail' })
    expect(liveTailBtn).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(liveTailBtn)

    expect(liveTailBtn).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Live tail on')).toBeInTheDocument()
    expect(screen.getByText('● Polling active')).toBeInTheDocument()
  })

  it('stops polling when disabled', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    const liveTailBtn = await screen.findByRole('button', { name: 'Live tail' })
    await userEvent.click(liveTailBtn)
    expect(liveTailBtn).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(liveTailBtn)
    expect(liveTailBtn).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('● Polling active')).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 11. Empty state
// ---------------------------------------------------------------------------
describe('LogsPage — empty state', () => {
  it('shows useful empty state when no logs match', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: [] },
    })

    expect(await screen.findByText('No logs found')).toBeInTheDocument()
    expect(screen.getByText('No logs match the current filters and time range.')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 12. API 503 Grafana unavailable — only real 503 triggers the state
// ---------------------------------------------------------------------------
describe('LogsPage — 503 unavailable state', () => {
  it('shows the server message when logs are unavailable, and the page keeps working', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': {
        status: 503,
        body: { status: 503, message: 'Logs are not set up on this server', errors: {} },
      },
    })

    expect(await screen.findByText('Logs unavailable')).toBeInTheDocument()
    expect(screen.getByText('Logs are not set up on this server. The rest of Settle keeps working.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toBeEnabled()
  })

  it('does NOT show "log store unavailable" for a 500 error whose message mentions 503', async () => {
    // Fix #5: 503 detection must use error.status === 503, not message string matching.
    renderLogs('/logs', {
      'GET /api/telemetry/logs': {
        status: 500,
        body: { status: 500, message: 'Internal error (not a real 503 / unavailable)' },
      },
    })

    expect(await screen.findByText('Failed to load logs')).toBeInTheDocument()
    // Must NOT show the unavailable message
    expect(screen.queryByText('Logs unavailable')).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 13. Other API error with retry
// ---------------------------------------------------------------------------
describe('LogsPage — generic error state', () => {
  it('displays error message and retry button on 500 error', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': {
        status: 500,
        body: { status: 500, message: 'Database connection failed' },
      },
    })

    expect(await screen.findByText('Failed to load logs')).toBeInTheDocument()
    expect(screen.getByText('Database connection failed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 14. Plain-text and malformed JSON safety
// ---------------------------------------------------------------------------
describe('LogsPage — malformed JSON & plain-text handling', () => {
  it('renders plain text and malformed JSON without crashing', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogsResponse },
    })

    // Plain text message
    expect(await screen.findByText('Payment processed successfully for user 456')).toBeInTheDocument()

    // Malformed JSON message
    expect(screen.getByText('{"broken json: true')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// Filters set while the text waits, and a window that moves with live tail
// ---------------------------------------------------------------------------
describe('LogsPage — debounce and time window', () => {
  it('keeps a level the URL gained while the typed text was still waiting', async () => {
    const { router } = renderLogs('/logs')
    await screen.findByRole('heading', { name: 'Logs Explorer' })

    fireEvent.change(screen.getByLabelText('Search text'), { target: { value: 'timeout' } })
    // Within the text's 400 ms wait, the level filter is set (as the Level select does).
    await act(() => router.navigate('/logs?level=ERROR'))

    await waitFor(() => expect(router.state.location.search).toContain('text=timeout'), { timeout: 1000 })
    expect(router.state.location.search).toContain('level=ERROR')
  })

  it('asks for the last 15 minutes at each request, not since the range was picked', async () => {
    const start = Date.parse('2026-11-08T10:00:00Z')
    const now = vi.spyOn(Date, 'now').mockReturnValue(start)
    try {
      const { fetchMock } = renderLogs('/logs?timeRange=15m')
      const froms = () => fetchMock.mock.calls
        .map(([input]) => String(input))
        .filter((url) => url.startsWith('/api/telemetry/logs'))
        .map((url) => new URL(url, 'http://localhost').searchParams.get('from'))
      await waitFor(() => expect(froms()).toHaveLength(1))

      // An hour later, the same view asks again.
      now.mockReturnValue(start + 60 * 60_000)
      await userEvent.click(screen.getByRole('button', { name: 'Refresh logs' }))
      await waitFor(() => expect(froms()).toHaveLength(2))

      expect(froms()).toEqual(['2026-11-08T09:45:00.000Z', '2026-11-08T10:45:00.000Z'])
    }
    finally {
      now.mockRestore()
    }
  })
})
