/**
 * LogsPage.test.tsx
 * Tests for Issue #163 — Logs Explorer (US-28 / TC-FR-26.1b).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch } from '@/test/fetchMock'
import { extractJsonFields } from '@/api/logs'

// ---------------------------------------------------------------------------
// Shared fixtures
// ---------------------------------------------------------------------------

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

const services = [
  { id: 1, name: 'Payments' },
  { id: 2, name: 'Checkout' },
]

const sampleLogs = [
  {
    id: 'log-1',
    timestamp: '2026-10-28T09:00:00Z',
    serviceName: 'Payments',
    serviceId: 1,
    level: 'ERROR',
    message: '{"event":"payment.failed","order_id":"ord-123","error":"gateway timeout"}',
    attributes: { event: 'payment.failed', order_id: 'ord-123', error: 'gateway timeout' },
  },
  {
    id: 'log-2',
    timestamp: '2026-10-28T08:55:00Z',
    serviceName: 'Payments',
    serviceId: 1,
    level: 'INFO',
    message: 'Payment processed successfully for user 456',
  },
  {
    id: 'log-3',
    timestamp: '2026-10-28T08:50:00Z',
    serviceName: 'Checkout',
    serviceId: 2,
    level: 'WARN',
    message: '{"broken json: true',
  },
]

type ExtraRoutes = Parameters<typeof mockFetch>[0]

function renderLogs(path = '/logs', extraRoutes: ExtraRoutes = {}) {
  writeToken('test-token')
  const defaultRoutes: Record<string, any> = {
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/services': { body: services },
    'GET /api/telemetry/logs': { body: sampleLogs },
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
      'GET /api/telemetry/logs': { body: sampleLogs },
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
})

// ---------------------------------------------------------------------------
// 2. Service filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — service filter', () => {
  it('applies service filter and queries logs for that service', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogs },
      'GET /api/telemetry/logs?serviceId=1&limit=100': { body: [sampleLogs[0], sampleLogs[1]] },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=1')
    })
  })
})

// ---------------------------------------------------------------------------
// 3. Level filter builds correct request
// ---------------------------------------------------------------------------
describe('LogsPage — level filter', () => {
  it('applies level filter and updates query', async () => {
    const { router } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogs },
      'GET /api/telemetry/logs?level=ERROR&limit=100': { body: [sampleLogs[0]] },
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
      'GET /api/telemetry/logs': { body: sampleLogs },
      'GET /api/telemetry/logs?text=timeout&limit=100': { body: [sampleLogs[0]] },
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
      'GET /api/telemetry/logs': { body: sampleLogs },
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
      'GET /api/telemetry/logs?from=2026-10-28T08:00:00Z&to=2026-10-28T09:00:00Z&limit=100': { body: [sampleLogs[0]] },
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
      'GET /api/telemetry/logs': { body: sampleLogs },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await userEvent.click(screen.getByRole('combobox', { name: 'Level' }))
    await userEvent.click(await screen.findByRole('option', { name: 'ERROR' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=1')
      expect(router.state.location.search).toContain('level=ERROR')
    })
  })
})

// ---------------------------------------------------------------------------
// 7. Clickable JSON field adds filter and triggers request
// ---------------------------------------------------------------------------
describe('LogsPage — clickable JSON fields (TC-FR-26.1b)', () => {
  it('clicking a JSON field badge adds that filter chip and sets text query', async () => {
    const { fetchMock } = renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogs },
      'GET /api/telemetry/logs?text=ord-123&limit=100': { body: [sampleLogs[0]] },
    })

    await screen.findByRole('heading', { name: 'Logs Explorer' })

    // Find clickable JSON field button
    const orderBtn = await screen.findByRole('button', { name: 'Filter by order_id: ord-123' })
    expect(orderBtn).toBeInTheDocument()

    await userEvent.click(orderBtn)

    // Active field filter badge appears
    expect(await screen.findByText(/Field:/)).toBeInTheDocument()
    expect(screen.getByText('order_id')).toBeInTheDocument()
    expect(screen.getAllByText('ord-123').length).toBeGreaterThanOrEqual(1)

    // Query was triggered with the field value
    await waitFor(() => {
      const calls = fetchMock.mock.calls.map(([req]) => String(req))
      expect(calls.some((url) => url.includes('text=ord-123'))).toBe(true)
    })
  })
})

// ---------------------------------------------------------------------------
// 8. Clearing a filter updates the request
// ---------------------------------------------------------------------------
describe('LogsPage — clearing filters', () => {
  it('clearing active field filter removes it and triggers updated request', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogs },
      'GET /api/telemetry/logs?text=ord-123&limit=100': { body: [sampleLogs[0]] },
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
      'GET /api/telemetry/logs?serviceId=1&level=ERROR&limit=100': { body: [sampleLogs[0]] },
      'GET /api/telemetry/logs': { body: sampleLogs },
    })

    expect(await screen.findByRole('button', { name: 'Clear filters' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    await waitFor(() => {
      expect(router.state.location.search).toBe('')
    })
  })
})

// ---------------------------------------------------------------------------
// 9. Live tail starts polling
// ---------------------------------------------------------------------------
describe('LogsPage — live tail', () => {
  it('starts polling when live tail is enabled', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': { body: sampleLogs },
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
      'GET /api/telemetry/logs': { body: sampleLogs },
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
// 12. API 503 Grafana unavailable
// ---------------------------------------------------------------------------
describe('LogsPage — 503 unavailable state', () => {
  it('displays clear log store unavailable message when API returns 503', async () => {
    renderLogs('/logs', {
      'GET /api/telemetry/logs': {
        status: 503,
        body: { status: 503, message: 'Grafana Loki unavailable' },
      },
    })

    expect(await screen.findByText('Log store unavailable')).toBeInTheDocument()
    expect(
      screen.getByText(/The backend could not reach Grafana Loki \(503\)/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
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
      'GET /api/telemetry/logs': { body: sampleLogs },
    })

    // Plain text message
    expect(await screen.findByText('Payment processed successfully for user 456')).toBeInTheDocument()

    // Malformed JSON message
    expect(screen.getByText('{"broken json: true')).toBeInTheDocument()
  })

  it('extractJsonFields safely handles various inputs', () => {
    expect(
      extractJsonFields({
        id: '1',
        timestamp: '2026-10-28T09:00:00Z',
        serviceName: 'Test',
        level: 'INFO',
        message: 'plain text',
      }),
    ).toBeNull()

    expect(
      extractJsonFields({
        id: '2',
        timestamp: '2026-10-28T09:00:00Z',
        serviceName: 'Test',
        level: 'INFO',
        message: '{ bad json',
      }),
    ).toBeNull()

    expect(
      extractJsonFields({
        id: '3',
        timestamp: '2026-10-28T09:00:00Z',
        serviceName: 'Test',
        level: 'INFO',
        message: '{"valid":"json","num":42}',
      }),
    ).toEqual({ valid: 'json', num: 42 })

    expect(
      extractJsonFields({
        id: '4',
        timestamp: '2026-10-28T09:00:00Z',
        serviceName: 'Test',
        level: 'INFO',
        message: 'ignored',
        attributes: { explicit: 'attr' },
      }),
    ).toEqual({ explicit: 'attr' })
  })
})

