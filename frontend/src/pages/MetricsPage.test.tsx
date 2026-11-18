/**
 * MetricsPage.test.tsx
 * Tests for Issue #163 — Sprint 4 Metrics Dashboard (FR-27 / TC-FR-27.3a).
 *
 * Requirements:
 * - Selected service and time range generate the correct API requests.
 * - URL parameters correctly preserve selected service and range.
 * - All four metric charts render correctly.
 * - Hover readout displays the correct values and units.
 * - HTTP 503 displays the server's error message (TC-FR-27.3a).
 * - The rest of the page remains usable when metrics are unavailable.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MetricName, MetricSeries } from '@/api/telemetry'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch } from '@/test/fetchMock'

// ---------------------------------------------------------------------------
// Shared fixtures
// ---------------------------------------------------------------------------

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

const services = [
  { id: 1, name: 'Payments' },
  { id: 2, name: 'Checkout' },
]

function makeSeries(
  metric: MetricName,
  values: number[],
  serviceId = 1,
  serviceName = 'Payments',
): MetricSeries {
  return {
    metric,
    serviceId,
    serviceName,
    from: '2026-10-28T08:00:00.000Z',
    to: '2026-10-28T09:00:00.000Z',
    stepSeconds: 60,
    points: values.map((value, i) => ({
      timestamp: new Date(Date.parse('2026-10-28T08:15:00.000Z') + i * 15 * 60_000).toISOString(),
      value,
    })),
  }
}

const sampleMetrics: Record<MetricName, MetricSeries> = {
  requestRate: makeSeries('requestRate', [2.5, 25.0, 18.2]),
  errorRate: makeSeries('errorRate', [0.01, 0.2, 0.05]),
  latencyP50: makeSeries('latencyP50', [0.035, 0.045, 0.04]),
  latencyP95: makeSeries('latencyP95', [0.12, 0.18, 0.15]),
}

/** Extracts the URL search params for every GET /api/telemetry/metrics request */
function metricRequests(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls
    .map(([input]) => String(input))
    .filter((url) => url.startsWith('/api/telemetry/metrics'))
    .map((url) => new URL(url, 'http://localhost').searchParams)
}

type ExtraRoutes = Parameters<typeof mockFetch>[0]

function renderMetrics(path = '/metrics', extraRoutes: ExtraRoutes = {}) {
  writeToken('test-token')

  const defaultRoutes: Record<string, any> = {
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/services': { body: services },
    'GET /api/telemetry/metrics': (_init: unknown, input: unknown) => {
      const url = new URL(String(input), 'http://localhost')
      const metric = url.searchParams.get('metric') as MetricName
      return { body: sampleMetrics[metric] ?? makeSeries(metric ?? 'requestRate', []) }
    },
    ...extraRoutes,
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const fullKey = `${method} ${String(input)}`
    const pathOnly = String(input).split('?')[0]
    const pathKey = `${method} ${pathOnly}`

    const handler = defaultRoutes[fullKey] ?? defaultRoutes[pathKey]
    if (!handler) {
      return new Response(JSON.stringify({ status: 404, message: `No mock for ${fullKey}` }), { status: 404 })
    }
    const route = typeof handler === 'function' ? handler(init, input) : handler
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
// 1. Rendering & Defaults
// ---------------------------------------------------------------------------
describe('MetricsPage — rendering & defaults', () => {
  it('renders page heading, filters, link to logs, and 4 metric charts', async () => {
    renderMetrics('/metrics')

    expect(await screen.findByRole('heading', { name: 'Metrics' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Time range' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open in Logs/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh metrics' })).toBeInTheDocument()

    // All four metric charts rendered ("Error rate" also names a tile, so the charts are found by their figures)
    expect(await screen.findByRole('img', { name: /^Request rate:/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /^Error rate:/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /^Latency p50:/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /^Latency p95:/ })).toBeInTheDocument()
  })

  it('shows the latest error rate, p95 latency and request rate as tiles', async () => {
    renderMetrics('/metrics')

    const tiles = await screen.findByRole('region', { name: 'Latest values' })
    expect(within(tiles).getByText('Error rate')).toBeInTheDocument()
    expect(within(tiles).getByText('5.00%')).toBeInTheDocument()
    expect(within(tiles).getByText('p95 latency')).toBeInTheDocument()
    expect(within(tiles).getByText('150 ms')).toBeInTheDocument()
    expect(within(tiles).getByText('Requests')).toBeInTheDocument()
    expect(within(tiles).getByText('18 req/s')).toBeInTheDocument()
  })

  it('names the pickers by their field, as form inputs are', async () => {
    renderMetrics('/metrics')

    await screen.findByRole('img', { name: /^Request rate:/ })
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveAttribute('id', 'serviceId')
    expect(screen.getByRole('combobox', { name: 'Time range' })).toHaveAttribute('id', 'timeRange')
  })

  it('defaults to the first service and 1 hour range when not specified in URL', async () => {
    const { fetchMock } = renderMetrics('/metrics')

    expect(await screen.findByText('Request rate')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveTextContent('Payments')
    expect(screen.getByRole('combobox', { name: 'Time range' })).toHaveTextContent('Last 1 hour')

    const reqs = metricRequests(fetchMock)
    expect(reqs.length).toBeGreaterThanOrEqual(4)
    for (const r of reqs) {
      expect(r.get('serviceId')).toBe('1')
    }
  })

  it('offers 1 hour, 6 hours, and 24 hours options in time range selector', async () => {
    renderMetrics('/metrics')
    await screen.findByRole('heading', { name: 'Metrics' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Time range' }))
    expect(await screen.findByRole('option', { name: 'Last 1 hour' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Last 6 hours' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Last 24 hours' })).toBeInTheDocument()
  })

  it('shows empty state when the org has no services yet', async () => {
    const { fetchMock } = renderMetrics('/metrics', { 'GET /api/services': { body: [] } })

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(metricRequests(fetchMock)).toHaveLength(0)
  })
})

// ---------------------------------------------------------------------------
// 2. Service Selection & URL Synchronization
// ---------------------------------------------------------------------------
describe('MetricsPage — service selection', () => {
  it('changing service updates URL search parameters and queries metrics for that service', async () => {
    const { router, fetchMock } = renderMetrics('/metrics')

    await screen.findByText('Request rate')

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Checkout' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=2')
      const latestReqs = metricRequests(fetchMock).slice(-4)
      for (const r of latestReqs) {
        expect(r.get('serviceId')).toBe('2')
      }
    })
  })

  it('respects serviceId provided in URL parameters', async () => {
    const { fetchMock } = renderMetrics('/metrics?serviceId=2')

    await screen.findByText('Request rate')
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveTextContent('Checkout')

    const reqs = metricRequests(fetchMock)
    expect(reqs.length).toBeGreaterThanOrEqual(4)
    for (const r of reqs) {
      expect(r.get('serviceId')).toBe('2')
    }
  })
})

// ---------------------------------------------------------------------------
// 3. Time-Range Selection & URL Synchronization
// ---------------------------------------------------------------------------
describe('MetricsPage — time-range selection', () => {
  it('changing time range updates URL search parameters and sends updated from window', async () => {
    const { router, fetchMock } = renderMetrics('/metrics')

    await screen.findByText('Request rate')

    await userEvent.click(screen.getByRole('combobox', { name: 'Time range' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Last 6 hours' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('timeRange=6h')
      const latestReqs = metricRequests(fetchMock).slice(-4)
      for (const r of latestReqs) {
        const from = Date.parse(r.get('from')!)
        const to = Date.parse(r.get('to')!)
        // Roughly 6 hours (within a few seconds)
        expect(to - from).toBeGreaterThanOrEqual(359 * 60_000)
        expect(to - from).toBeLessThanOrEqual(361 * 60_000)
      }
    })
  })

  it('respects timeRange provided in URL parameters', async () => {
    const { fetchMock } = renderMetrics('/metrics?timeRange=24h')

    await screen.findByText('Request rate')
    expect(screen.getByRole('combobox', { name: 'Time range' })).toHaveTextContent('Last 24 hours')

    const reqs = metricRequests(fetchMock)
    expect(reqs.length).toBeGreaterThanOrEqual(4)
    for (const r of reqs) {
      const from = Date.parse(r.get('from')!)
      const to = Date.parse(r.get('to')!)
      // Roughly 24 hours
      expect(to - from).toBeGreaterThanOrEqual(1439 * 60_000)
      expect(to - from).toBeLessThanOrEqual(1441 * 60_000)
    }
  })
})

// ---------------------------------------------------------------------------
// 4. Hover Readout with Values and Units
// ---------------------------------------------------------------------------
describe('MetricsPage — chart rendering and hover readout', () => {
  it('displays correct units in figures: req/s, %, and ms', async () => {
    renderMetrics('/metrics')

    await screen.findByText('Request rate')

    // Peak readouts
    expect(screen.getByText('Peak 25 req/s')).toBeInTheDocument()
    expect(screen.getByText('Peak 20.0%')).toBeInTheDocument()
    expect(screen.getByText('Peak 45 ms')).toBeInTheDocument()
    expect(screen.getByText('Peak 180 ms')).toBeInTheDocument()

    // Latest readouts before hover
    expect(screen.getByText('Latest 18 req/s')).toBeInTheDocument()
    expect(screen.getByText('Latest 5.00%')).toBeInTheDocument()
    expect(screen.getByText('Latest 40 ms')).toBeInTheDocument()
    expect(screen.getByText('Latest 150 ms')).toBeInTheDocument()
  })

  it('hover readout displays formatted IST timestamp and unit on mouse move', async () => {
    renderMetrics('/metrics')

    await screen.findByText('Request rate')

    // Find the SVG for Request rate
    const reqRateSvg = screen.getByRole('img', { name: /^Request rate:/ })
    expect(reqRateSvg).toBeInTheDocument()

    // Hover over the first point region (approx x=0)
    const figure = reqRateSvg.closest('figure')!
    fireEvent.mouseMove(reqRateSvg, { clientX: 0 })

    // Readout updates to point 0: 2.50 req/s
    expect(within(figure).getByText(/· 2\.50 req\/s/)).toBeInTheDocument()

    // Hover over the second point region (approx x=150)
    fireEvent.mouseMove(reqRateSvg, { clientX: 150 })
    expect(within(figure).getByText(/· 25 req\/s/)).toBeInTheDocument()

    // Mouse leave restores "Latest"
    fireEvent.mouseLeave(reqRateSvg)
    expect(within(figure).getByText('Latest 18 req/s')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 5. HTTP 503 State & Resilience (TC-FR-27.3a)
// ---------------------------------------------------------------------------
describe('MetricsPage — 503 unavailable state (TC-FR-27.3a)', () => {
  it('displays the actual server-provided message on HTTP 503 and keeps page usable', async () => {
    const serverMessage = 'Metrics are not set up on this server'
    renderMetrics('/metrics', {
      'GET /api/telemetry/metrics': {
        status: 503,
        body: { status: 503, message: serverMessage, errors: {} },
      },
    })

    expect(await screen.findByRole('heading', { name: 'Metrics' })).toBeInTheDocument()
    expect(await screen.findByText('Metrics unavailable')).toBeInTheDocument()
    expect(
      screen.getByText(`${serverMessage}. The rest of Settle keeps working.`),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()

    // The rest of the page remains functional: controls are enabled
    expect(screen.getByRole('combobox', { name: 'Service' })).toBeEnabled()
    expect(screen.getByRole('combobox', { name: 'Time range' })).toBeEnabled()
    expect(screen.getByRole('link', { name: /Open in Logs/i })).toBeInTheDocument()
  })

  it('does NOT treat a 500 error as 503 unavailable', async () => {
    renderMetrics('/metrics', {
      'GET /api/telemetry/metrics': {
        status: 500,
        body: { status: 500, message: 'Prometheus query failed' },
      },
    })

    expect(await screen.findByText('Failed to load metrics')).toBeInTheDocument()
    expect(screen.getByText('Prometheus query failed')).toBeInTheDocument()
    expect(screen.queryByText('Metrics unavailable')).not.toBeInTheDocument()
  })
})

describe('MetricsPage — URL values that are not options', () => {
  it('falls back to the first service and the last hour for an unknown serviceId and timeRange', async () => {
    const { fetchMock } = renderMetrics('/metrics?serviceId=999&timeRange=2d')

    await screen.findByRole('img', { name: /^Request rate:/ })
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveTextContent('Payments')
    expect(screen.getByRole('combobox', { name: 'Time range' })).toHaveTextContent('Last 1 hour')
    const reqs = metricRequests(fetchMock)
    expect(reqs.length).toBeGreaterThanOrEqual(4)
    for (const r of reqs) {
      expect(r.get('serviceId')).toBe('1')
      const span = Date.parse(r.get('to')!) - Date.parse(r.get('from')!)
      expect(span).toBe(60 * 60_000)
    }
  })

  it('ignores from and to in the URL instead of crashing on them', async () => {
    const { fetchMock } = renderMetrics('/metrics?from=abc&to=xyz')

    expect(await screen.findByRole('img', { name: /^Request rate:/ })).toBeInTheDocument()
    for (const r of metricRequests(fetchMock)) {
      expect(r.get('from')).not.toBe('abc')
      expect(Number.isNaN(Date.parse(r.get('from')!))).toBe(false)
    }
  })
})

describe('MetricsPage — refresh', () => {
  it('loads each metric once per click', async () => {
    const { fetchMock } = renderMetrics('/metrics')

    await screen.findByRole('img', { name: /^Request rate:/ })
    const before = metricRequests(fetchMock).length
    await userEvent.click(screen.getByRole('button', { name: 'Refresh metrics' }))
    await waitFor(() => expect(metricRequests(fetchMock).length).toBe(before + 4))
    // Nothing else arrives after the four new requests.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(metricRequests(fetchMock).length).toBe(before + 4)
  })
})
