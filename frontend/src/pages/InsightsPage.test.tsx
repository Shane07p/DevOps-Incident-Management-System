import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InsightsResponse } from '@/api/insights'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, users } from '@/test/fetchMock'
import { InsightsPage } from './InsightsPage'

const baseFixture: InsightsResponse = {
  from: '2026-10-15T00:00:00Z',
  to: '2026-11-14T00:00:00Z',
  incidents: {
    total: 42,
    open: 5,
    resolved: 37,
  },
  mtta: {
    mean: 14.5,
    median: 12.0,
    p90: 28.0,
  },
  mttr: {
    mean: 135.0,
    median: 95.0,
    p90: 260.0,
  },
  bySeverity: [
    { severity: 'SEV1', count: 3 },
    { severity: 'SEV2', count: 9 },
    { severity: 'SEV3', count: 18 },
    { severity: 'SEV4', count: 12 },
  ],
  byService: [
    { serviceId: 1, name: 'Payments', count: 15 },
    { serviceId: 2, name: 'Auth', count: 8 },
  ],
  slaBreachRate: {
    response: 0.048,
    resolution: 0.095,
  },
  followUps: {
    total: 20,
    done: 17,
    overdue: 2,
    completionRate: 0.85,
  },
  outOfHoursPages: 7,
}

afterEach(() => {
  cleanup()
  clearToken()
  vi.unstubAllGlobals()
})

function renderInsights(path = '/insights', queryClient?: QueryClient) {
  const router = createMemoryRouter(
    [
      {
        path: '/insights',
        element: <InsightsPage />,
      },
      {
        path: '/services/:id',
        element: <div>Service Detail</div>,
      },
    ],
    { initialEntries: [path] },
  )
  const client = queryClient ?? new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient: client }
}

function renderAppWithAuth(path = '/insights', extraRoutes: Parameters<typeof mockFetch>[0] = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: users[0], org: devOrg } },
    'GET /api/insights?days=30': { body: baseFixture },
    ...extraRoutes,
  })
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
  return { fetchMock, router }
}

describe('InsightsPage - Integration with router', () => {
  it('renders the insights page at /insights route', async () => {
    renderAppWithAuth('/insights')
    expect(await screen.findByRole('heading', { level: 1, name: 'Insights' })).toBeInTheDocument()
    expect(screen.getByText(/Incident volume, MTTA, MTTR, SLA performance/i)).toBeInTheDocument()
  })
})

describe('InsightsPage - URL and date ranges', () => {
  it('defaults to 30-day range request when no parameter is present', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
    })
    renderInsights('/insights')

    await screen.findByRole('heading', { level: 1, name: 'Insights' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=30',
      expect.objectContaining({ method: 'GET' }),
    )
    const btn30 = screen.getByRole('button', { name: '30 days' })
    expect(btn30).toHaveAttribute('aria-pressed', 'true')
  })

  it('loads 7-day data when ?days=7 is present in the URL', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=7': { body: { ...baseFixture, outOfHoursPages: 2 } },
    })
    renderInsights('/insights?days=7')

    await screen.findByRole('heading', { level: 1, name: 'Insights' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=7',
      expect.objectContaining({ method: 'GET' }),
    )
    const btn7 = screen.getByRole('button', { name: '7 days' })
    expect(btn7).toHaveAttribute('aria-pressed', 'true')
  })

  it('loads 90-day data when ?days=90 is present in the URL', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=90': { body: { ...baseFixture, outOfHoursPages: 18 } },
    })
    renderInsights('/insights?days=90')

    await screen.findByRole('heading', { level: 1, name: 'Insights' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=90',
      expect.objectContaining({ method: 'GET' }),
    )
    const btn90 = screen.getByRole('button', { name: '90 days' })
    expect(btn90).toHaveAttribute('aria-pressed', 'true')
  })

  it('falls back to 30 days when URL has an invalid range parameter', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
    })
    renderInsights('/insights?days=invalid')

    await screen.findByRole('heading', { level: 1, name: 'Insights' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=30',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('updates the URL and triggers a new API request when changing the range', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
      'GET /api/insights?days=7': { body: { ...baseFixture, outOfHoursPages: 1 } },
      'GET /api/insights?days=90': { body: { ...baseFixture, outOfHoursPages: 25 } },
    })
    const { router } = renderInsights('/insights')

    await screen.findByText('15 min') // Mean MTTA rendered
    expect(screen.getByText('7')).toBeInTheDocument() // Out-of-hours pages 7

    // Click 7 days
    await userEvent.click(screen.getByRole('button', { name: '7 days' }))
    expect(router.state.location.search).toBe('?days=7')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=7',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(await screen.findByText('1')).toBeInTheDocument() // 7-day out-of-hours pages

    // Click 90 days
    await userEvent.click(screen.getByRole('button', { name: '90 days' }))
    expect(router.state.location.search).toBe('?days=90')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=90',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(await screen.findByText('25')).toBeInTheDocument() // 90-day out-of-hours pages
  })

  it('preserves unrelated existing URL parameters when changing the range', async () => {
    mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
      'GET /api/insights?days=7': { body: baseFixture },
    })
    const { router } = renderInsights('/insights?service=payments&team=infra')

    await screen.findByRole('heading', { level: 1, name: 'Insights' })
    await userEvent.click(screen.getByRole('button', { name: '7 days' }))

    const params = new URLSearchParams(router.state.location.search)
    expect(params.get('days')).toBe('7')
    expect(params.get('service')).toBe('payments')
    expect(params.get('team')).toBe('infra')
  })
})

describe('InsightsPage - Successful response rendering', () => {
  it('renders all metrics, formatted times, rates, counters, and breakdown bars', async () => {
    mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
    })
    renderInsights('/insights')

    // MTTA: Mean 14.5 -> 15 min, Median 12 -> 12 min, P90 28 -> 28 min
    expect(await screen.findByText('15 min')).toBeInTheDocument()
    expect(screen.getByText('12 min')).toBeInTheDocument()
    expect(screen.getByText('28 min')).toBeInTheDocument()

    // MTTR: Mean 135 -> 2 h 15 min, Median 95 -> 1 h 35 min, P90 260 -> 4 h 20 min
    expect(screen.getByText('2 h 15 min')).toBeInTheDocument()
    expect(screen.getByText('1 h 35 min')).toBeInTheDocument()
    expect(screen.getByText('4 h 20 min')).toBeInTheDocument()

    // SLA breach rates: response 0.048 -> 4.8%, resolution 0.095 -> 9.5%
    expect(screen.getByText('4.8%')).toBeInTheDocument()
    expect(screen.getByText('9.5%')).toBeInTheDocument()

    // Follow-ups: completionRate 0.85 -> 85.0%, total 20, done 17, overdue 2
    expect(screen.getByText('85.0%')).toBeInTheDocument()
    expect(screen.getByText('20')).toBeInTheDocument()
    expect(screen.getByText('17')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()

    // Out-of-hours pages: 7
    expect(screen.getByText('7')).toBeInTheDocument()

    // Incident volume: total 42, open 5, resolved 37
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('37')).toBeInTheDocument()

    // Severity breakdown items: SEV1 (3), SEV2 (9), SEV3 (18), SEV4 (12)
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('18')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()

    // Severity percentages: 3/42=7.1%, 9/42=21.4%, 18/42=42.9%, 12/42=28.6%
    expect(screen.getByText('7.1%')).toBeInTheDocument()
    expect(screen.getByText('21.4%')).toBeInTheDocument()
    expect(screen.getByText('42.9%')).toBeInTheDocument()
    expect(screen.getByText('28.6%')).toBeInTheDocument()

    // Service breakdown items: Payments (15), Auth (8)
    expect(screen.getByText('Payments')).toBeInTheDocument()
    expect(screen.getByText('15')).toBeInTheDocument()
    expect(screen.getByText('Auth')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()

    // Service breakdown rows link to /services/{serviceId}
    const paymentsLink = screen.getByRole('link', { name: /Payments/i })
    expect(paymentsLink).toHaveAttribute('href', '/services/1')
    const authLink = screen.getByRole('link', { name: /Auth/i })
    expect(authLink).toHaveAttribute('href', '/services/2')
  })

  it('navigates to /services/{serviceId} when a service row is clicked', async () => {
    mockFetch({
      'GET /api/insights?days=30': { body: baseFixture },
    })
    const { router } = renderInsights('/insights')

    const paymentsLink = await screen.findByRole('link', { name: /Payments/i })
    await userEvent.click(paymentsLink)

    expect(router.state.location.pathname).toBe('/services/1')
  })
})

describe('InsightsPage - Missing values handling', () => {
  it('displays exactly "No data yet" when metrics or rates are null', async () => {
    const nullFixture: InsightsResponse = {
      from: '2026-10-15T00:00:00Z',
      to: '2026-11-14T00:00:00Z',
      incidents: {
        total: 0,
        open: 0,
        resolved: 0,
      },
      mtta: {
        mean: null,
        median: null,
        p90: null,
      },
      mttr: {
        mean: null,
        median: null,
        p90: null,
      },
      bySeverity: [],
      byService: [],
      slaBreachRate: {
        response: null,
        resolution: null,
      },
      followUps: {
        total: 0,
        done: 0,
        overdue: 0,
        completionRate: null,
      },
      outOfHoursPages: 0,
    }

    mockFetch({
      'GET /api/insights?days=30': { body: nullFixture },
    })
    renderInsights('/insights')

    // Wait for data to load and assert "No data yet" for all null metrics
    const noDataElements = await screen.findAllByText('No data yet')
    // We expect "No data yet" for:
    // MTTA mean, median, p90 (3)
    // MTTR mean, median, p90 (3)
    // SLA response, resolution (2)
    // Follow-up completionRate (1)
    // Severity breakdown empty/zero total (1)
    // Service breakdown empty (1)
    expect(noDataElements.length).toBeGreaterThanOrEqual(10)

    // Ensure it never rendered 0 min, 0.0%, or NaN
    expect(screen.queryByText('0 min')).not.toBeInTheDocument()
    expect(screen.queryByText('0.0%')).not.toBeInTheDocument()
    expect(screen.queryByText(/NaN/i)).not.toBeInTheDocument()
  })
})

describe('InsightsPage - Zero values handling', () => {
  it('displays genuine numeric zero counts as 0 rather than "No data yet"', async () => {
    const zeroCountsFixture: InsightsResponse = {
      ...baseFixture,
      incidents: {
        total: 10,
        open: 0,
        resolved: 10,
      },
      followUps: {
        total: 5,
        done: 5,
        overdue: 0,
        completionRate: 1.0,
      },
      outOfHoursPages: 0,
    }

    mockFetch({
      'GET /api/insights?days=30': { body: zeroCountsFixture },
    })
    renderInsights('/insights')

    // Zero open incidents, zero overdue follow-ups, zero outOfHoursPages should render as 0
    const zeroElements = await screen.findAllByText('0')
    expect(zeroElements.length).toBeGreaterThanOrEqual(3)
  })
})

describe('InsightsPage - Loading and error states', () => {
  it('shows loading state while query is pending', async () => {
    let resolvePending!: (v: Response) => void
    const pendingPromise = new Promise<Response>((res) => {
      resolvePending = res
    })
    vi.stubGlobal('fetch', vi.fn(async () => pendingPromise))

    renderInsights('/insights')

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByText(/Loading insights/i)).toBeInTheDocument()

    // Resolve so cleanup completes cleanly
    resolvePending(
      new Response(JSON.stringify(baseFixture), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })

  it('shows error state on API failure and allows retry', async () => {
    let attempts = 0
    mockFetch({
      'GET /api/insights?days=30': () => {
        attempts++
        if (attempts === 1) {
          return { status: 500, body: { message: 'Server unavailable' } }
        }
        return { status: 200, body: baseFixture }
      },
    })
    renderInsights('/insights')

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('Server unavailable')).toBeInTheDocument()

    // Click retry
    const retryBtn = screen.getByRole('button', { name: /Try again/i })
    await userEvent.click(retryBtn)

    // Should now show the data
    expect(await screen.findByText('15 min')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
