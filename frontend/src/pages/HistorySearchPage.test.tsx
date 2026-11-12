/**
 * HistorySearchPage.test.tsx
 * Tests for Issue #168 — History Search Page (US-15).
 *
 * Covers all 14 required test scenarios without a running backend.
 * API responses are mocked with the same mockFetch helper used across the project.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch } from '@/test/fetchMock'
import { HistorySearchPage } from './HistorySearchPage'

// ---------------------------------------------------------------------------
// Shared fixtures
// ---------------------------------------------------------------------------

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

const servicesBody = [
  { id: 1, name: 'Payments' },
  { id: 2, name: 'Checkout' },
]

const makeResult = (overrides: Record<string, unknown> = {}) => ({
  id: 7,
  reference: 'INC-7',
  title: 'Checkout failing',
  serviceName: 'Payments',
  serviceId: 1,
  severity: 'SEV2',
  status: 'OPEN',
  createdAt: '2026-10-20T08:30:00Z',
  matchedIn: ['title'],
  ...overrides,
})

const pageOf = (content: unknown[], total?: number, page = 0, size = 20) => ({
  content,
  page,
  size,
  totalElements: total ?? content.length,
  totalPages: Math.ceil((total ?? content.length) / size),
})

const emptyPage = pageOf([])

// ---------------------------------------------------------------------------
// Render helper
// ---------------------------------------------------------------------------

type ExtraRoutes = Parameters<typeof mockFetch>[0]

function renderHistory(path = '/history', extraRoutes: ExtraRoutes = {}) {
  writeToken('test-token')

  const defaultRoutes: Record<string, any> = {
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/services': { body: servicesBody },
    'GET /api/incidents/search': { body: emptyPage },
    ...extraRoutes,
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const fullKey = `${method} ${String(input)}`
    const unencodedKey = `${method} ${decodeURIComponent(String(input))}`
    const withoutPageKey = `${method} ${String(input).replace(/[?&]page=\d+/, '').replace(/\?$/, '')}`
    const pathOnly = String(input).split('?')[0]
    const pathKey = `${method} ${pathOnly}`

    const handler = defaultRoutes[fullKey] ?? defaultRoutes[unencodedKey] ?? defaultRoutes[withoutPageKey] ?? defaultRoutes[pathKey]
    if (!handler) return new Response(JSON.stringify({ status: 404, message: `No mock for ${fullKey}` }), { status: 404 })
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

  return { fetchMock, router }
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

// ---------------------------------------------------------------------------
// 1. Page renders correctly
// ---------------------------------------------------------------------------
describe('HistorySearchPage — rendering', () => {
  it('renders the page heading and search controls', async () => {
    renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
    })

    expect(await screen.findByRole('heading', { name: 'History' })).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: 'Search incidents' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Severity' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Status' })).toBeInTheDocument()
  })

  it('shows results when search returns data', async () => {
    renderHistory('/history', {
      'GET /api/incidents/search': { body: pageOf([makeResult()]) },
    })

    expect(await screen.findByRole('link', { name: 'Checkout failing' })).toBeInTheDocument()
    expect(screen.getByText('INC-7')).toBeInTheDocument()
    expect(screen.getByText('1 incident found')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 2. Search query in URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — keyword search URL sync', () => {
  it('puts the search keyword in the URL', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?q=payment': { body: pageOf([makeResult({ title: 'Payment slow' })]) },
    })

    await screen.findByRole('heading', { name: 'History' })

    const input = screen.getByRole('searchbox', { name: 'Search incidents' })
    await userEvent.type(input, 'payment')

    // Debounce fires after 400 ms
    await waitFor(() => expect(router.state.location.search).toContain('q=payment'), { timeout: 1000 })
  })
})

describe('HistorySearchPage — keyword and filter together (FR-17.1)', () => {
  it('keeps a filter the URL gained while the keyword was still waiting to be searched', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
      String(input) === '/api/services' ? [] : emptyPage,
    ), { status: 200 })))
    const router = createMemoryRouter([{ path: '/history', element: <HistorySearchPage /> }], { initialEntries: ['/history'] })
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search incidents' }), { target: { value: 'payment' } })
    // Within the keyword's 400 ms wait, the severity filter is set (as the Severity select does).
    await act(() => router.navigate('/history?severity=SEV1'))
    expect(router.state.location.search).toBe('?severity=SEV1')

    await waitFor(() => expect(router.state.location.search).toContain('q=payment'), { timeout: 1000 })
    expect(router.state.location.search).toContain('severity=SEV1')
  })
})

// ---------------------------------------------------------------------------
// 3. Service filter in URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — service filter URL sync', () => {
  it('puts serviceId in the URL when a service is chosen', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?serviceId=1': { body: pageOf([makeResult()]) },
    })

    await screen.findByRole('heading', { name: 'History' })
    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await waitFor(() => expect(router.state.location.search).toContain('serviceId=1'))
  })
})

// ---------------------------------------------------------------------------
// 4. Severity filter in URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — severity filter URL sync', () => {
  it('puts severity in the URL when a severity is chosen', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?severity=SEV1': { body: emptyPage },
    })

    await screen.findByRole('heading', { name: 'History' })
    await userEvent.click(screen.getByRole('combobox', { name: 'Severity' }))
    await userEvent.click(await screen.findByRole('option', { name: 'SEV1' }))

    await waitFor(() => expect(router.state.location.search).toContain('severity=SEV1'))
  })
})

// ---------------------------------------------------------------------------
// 5. Status filter in URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — status filter URL sync', () => {
  it('puts status in the URL when a status is chosen', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?status=RESOLVED': { body: emptyPage },
    })

    await screen.findByRole('heading', { name: 'History' })
    await userEvent.click(screen.getByRole('combobox', { name: 'Status' }))
    await userEvent.click(await screen.findByRole('option', { name: 'RESOLVED' }))

    await waitFor(() => expect(router.state.location.search).toContain('status=RESOLVED'))
  })
})

// ---------------------------------------------------------------------------
// 6. Multiple filters together
// ---------------------------------------------------------------------------
describe('HistorySearchPage — combined filters', () => {
  it('combines service and severity in the URL', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?serviceId=1': { body: emptyPage },
      'GET /api/incidents/search?serviceId=1&severity=SEV2': { body: pageOf([makeResult()]) },
    })

    await screen.findByRole('heading', { name: 'History' })

    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))
    await waitFor(() => expect(router.state.location.search).toContain('serviceId=1'))

    await userEvent.click(screen.getByRole('combobox', { name: 'Severity' }))
    await userEvent.click(await screen.findByRole('option', { name: 'SEV2' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('serviceId=1')
      expect(router.state.location.search).toContain('severity=SEV2')
    })
  })
})

// ---------------------------------------------------------------------------
// 7. Page reload restores filter values from URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — URL to state restoration', () => {
  it('restores search input value and filter values from the URL on load', async () => {
    renderHistory('/history?q=timeout&severity=SEV1&status=RESOLVED', {
      'GET /api/incidents/search?q=timeout&severity=SEV1&status=RESOLVED': { body: emptyPage },
    })

    // Input value restored from URL
    const input = await screen.findByRole('searchbox', { name: 'Search incidents' })
    expect(input).toHaveValue('timeout')

    // Filter dropdowns show the restored values
    expect(screen.getByRole('combobox', { name: 'Severity' })).toHaveTextContent('SEV1')
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveTextContent('RESOLVED')
  })
})

// ---------------------------------------------------------------------------
// 8. Browser back / forward
// ---------------------------------------------------------------------------
describe('HistorySearchPage — back/forward navigation', () => {
  it('restores the previous search state when navigating back', async () => {
    const { router } = renderHistory('/history', {
      'GET /api/incidents/search': { body: emptyPage },
      'GET /api/incidents/search?severity=SEV2': { body: emptyPage },
    })

    await screen.findByRole('heading', { name: 'History' })
    await userEvent.click(screen.getByRole('combobox', { name: 'Severity' }))
    await userEvent.click(await screen.findByRole('option', { name: 'SEV2' }))
    await waitFor(() => expect(router.state.location.search).toContain('severity=SEV2'))

    // Navigate back
    await act(async () => {
      router.navigate(-1)
    })
    await waitFor(() => expect(router.state.location.search).not.toContain('severity=SEV2'))
    expect(router.state.location.search).toBe('')
  })
})

// ---------------------------------------------------------------------------
// 9. Empty state
// ---------------------------------------------------------------------------
describe('HistorySearchPage — empty state', () => {
  it('shows the empty state when no results are returned', async () => {
    renderHistory('/history?q=xyz', {
      'GET /api/incidents/search?q=xyz': { body: emptyPage },
    })

    expect(await screen.findByText('No matching incidents')).toBeInTheDocument()
    expect(screen.getByText(/No historical incidents match/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear search and filters' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 10. Clearing filters resets the URL
// ---------------------------------------------------------------------------
describe('HistorySearchPage — clear filters', () => {
  it('clears the URL and resets the search input when Clear filters is clicked', async () => {
    const { router } = renderHistory('/history?q=payment&severity=SEV1', {
      'GET /api/incidents/search?q=payment&severity=SEV1': { body: emptyPage },
      'GET /api/incidents/search': { body: emptyPage },
    })

    expect(await screen.findByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.getByRole('searchbox', { name: 'Search incidents' })).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 11. Clicking an incident opens its detail page
// ---------------------------------------------------------------------------
describe('HistorySearchPage — incident navigation', () => {
  it('the incident title link navigates to the incident detail page', async () => {
    renderHistory('/history', {
      'GET /api/incidents/search': { body: pageOf([makeResult()]) },
      'GET /api/incidents/7': {
        body: {
          id: 7,
          reference: 'INC-7',
          title: 'Checkout failing',
          description: 'Payments broken.',
          service: { id: 1, name: 'Payments' },
          severity: 'SEV2',
          status: 'OPEN',
          reporter: { id: 2, displayName: 'Dev Engineer' },
          assignee: null,
          createdAt: '2026-10-20T08:30:00Z',
          responseTargetMinutes: 60,
          resolutionTargetMinutes: 480,
          responseDueAt: '2026-10-20T09:30:00Z',
          resolutionDueAt: '2026-10-20T16:30:00Z',
          timeline: [],
        },
      },
    })

    const link = await screen.findByRole('link', { name: 'Checkout failing' })
    expect(link).toHaveAttribute('href', '/incidents/7')

    await userEvent.click(link)
    expect(await screen.findByRole('heading', { name: 'Checkout failing' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 12. Loading and error states
// ---------------------------------------------------------------------------
describe('HistorySearchPage — async states', () => {
  it('shows a loading spinner while the search is pending', async () => {
    let resolveSearch!: (v: Response) => void
    const searchPromise = new Promise<Response>((res) => {
      resolveSearch = res
    })

    writeToken('test-token')
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const key = `${init?.method ?? 'GET'} ${String(input)}`
      if (key === 'GET /api/auth/me') {
        return new Response(JSON.stringify({ user: viewer, org: devOrg }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (key === 'GET /api/services') {
        return new Response(JSON.stringify(servicesBody), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (key.startsWith('GET /api/incidents/search')) {
        return searchPromise
      }
      return new Response(JSON.stringify({ unreadCount: 0, notifications: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = createMemoryRouter(routes, { initialEntries: ['/history'] })
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

    expect(await screen.findByText('Searching incidents…')).toBeInTheDocument()
    // Resolve the hanging promise so React can clean up
    resolveSearch(new Response(JSON.stringify(emptyPage), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  })

  it('shows an error state with a retry button on API failure', async () => {
    renderHistory('/history', {
      'GET /api/incidents/search': { status: 500, body: { status: 500, message: 'Internal server error' } },
    })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 13. Match location displayed correctly
// ---------------------------------------------------------------------------
describe('HistorySearchPage — match location', () => {
  it('shows match-location badges for each result when a keyword was searched', async () => {
    renderHistory('/history?q=timeout', {
      'GET /api/incidents/search?q=timeout': {
        body: pageOf([
          makeResult({ matchedIn: ['title', 'timeline'] }),
        ]),
      },
    })

    await screen.findByRole('link', { name: 'Checkout failing' })

    // Query badges within the result row
    const row = await screen.findByRole('row', { name: /Checkout failing/ })
    expect(within(row).getByText('Title')).toBeInTheDocument()
    expect(within(row).getByText('Timeline')).toBeInTheDocument()
  })

  it('shows description badge when description matched', async () => {
    renderHistory('/history?q=broken', {
      'GET /api/incidents/search?q=broken': {
        body: pageOf([makeResult({ matchedIn: ['description'] })]),
      },
    })

    const row = await screen.findByRole('row', { name: /Checkout failing/ })
    expect(within(row).getByText('Description')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 14. Pagination preserves active filters
// ---------------------------------------------------------------------------
describe('HistorySearchPage — pagination', () => {
  it('adds the page number to the URL when navigating to the next page, preserving filters', async () => {
    const bigPage = pageOf(
      Array.from({ length: 20 }, (_, i) => makeResult({ id: i + 1, reference: `INC-${i + 1}`, title: `Incident ${i + 1}` })),
      55, // total > 20 so pagination appears
    )
    const { router } = renderHistory('/history?severity=SEV2', {
      'GET /api/incidents/search?severity=SEV2&page=0': { body: bigPage },
      'GET /api/incidents/search?severity=SEV2&page=1': { body: pageOf([], 55, 1) },
    })

    expect(await screen.findByText('55 incidents found')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Pagination' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('severity=SEV2')
      expect(router.state.location.search).toContain('page=2')
    })
  })
})

// ---------------------------------------------------------------------------
// 15. Shane review fixes validation (Issue #210)
// ---------------------------------------------------------------------------
describe('HistorySearchPage — Shane review feedback fixes', () => {
  it('Fix 2: converts 1-based UI pages to 0-based backend pages in API queries', async () => {
    // UI page 1 -> backend page 0
    const { fetchMock } = renderHistory('/history?page=1')
    await waitFor(() => {
      const calls = fetchMock.mock.calls.map(([req]) => String(req))
      expect(calls.some((url) => url.includes('/api/incidents/search') && url.includes('page=0'))).toBe(true)
    })

    // UI page 2 -> backend page 1
    const { fetchMock: mock2 } = renderHistory('/history?page=2')
    await waitFor(() => {
      const calls = mock2.mock.calls.map(([req]) => String(req))
      expect(calls.some((url) => url.includes('/api/incidents/search') && url.includes('page=1'))).toBe(true)
    })

    // UI page 3 -> backend page 2
    const { fetchMock: mock3 } = renderHistory('/history?page=3')
    await waitFor(() => {
      const calls = mock3.mock.calls.map(([req]) => String(req))
      expect(calls.some((url) => url.includes('/api/incidents/search') && url.includes('page=2'))).toBe(true)
    })
  })

  it('Fix 3: omits q entirely when search query is empty while sending other filters', async () => {
    const { fetchMock } = renderHistory('/history?serviceId=1&severity=SEV1&status=OPEN')
    await waitFor(() => {
      const calls = fetchMock.mock.calls.map(([req]) => String(req))
      const searchCall = calls.find((url) => url.includes('/api/incidents/search'))
      expect(searchCall).toBeDefined()
      expect(searchCall).not.toContain('q=')
      expect(searchCall).toContain('serviceId=1')
      expect(searchCall).toContain('severity=SEV1')
      expect(searchCall).toContain('status=OPEN')
      expect(searchCall).toContain('page=0')
    })
  })

  it('Fix 4: accepts results with or without serviceId (PR #209 backend contract)', async () => {
    // Result without serviceId (PR #209 current state)
    const resultWithoutServiceId = {
      id: 8,
      reference: 'INC-8',
      title: 'Checkout timeout',
      serviceName: 'Checkout',
      severity: 'SEV1',
      status: 'OPEN',
      createdAt: '2026-10-20T08:30:00Z',
    }

    // Result with serviceId (PR #209 agreed future contract)
    const resultWithServiceId = makeResult({ id: 9, reference: 'INC-9', title: 'Payment latency', serviceId: 1 })

    renderHistory('/history', {
      'GET /api/incidents/search': {
        body: pageOf([resultWithoutServiceId, resultWithServiceId]),
      },
    })

    expect(await screen.findByRole('link', { name: 'Checkout timeout' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Payment latency' })).toBeInTheDocument()
    expect(screen.getByText('2 incidents found')).toBeInTheDocument()
  })
})

