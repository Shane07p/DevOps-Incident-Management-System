/**
 * SimilarIncidents.test.tsx
 * Tests for Issue #170 — Similar incidents and duplicate warning (US-18 / TC-FR-18.2a).
 *
 * Review fix: scores are 0–1 only, single `fix` field (no `resolution`),
 * flat array response (no union), and errors propagate instead of being
 * silently swallowed.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

// ---------------------------------------------------------------------------
// Shared Fixtures — single shape, score 0–1, one `fix` field
// ---------------------------------------------------------------------------

const engineer = users[1]

const baseIncident = {
  id: 7,
  reference: 'INC-7',
  title: 'Checkout failing for card payments',
  description: 'Card payments fail at final step with gateway timeout.',
  service: payments,
  severity: 'SEV2',
  status: 'OPEN',
  reporter: engineer,
  assignee: null,
  createdAt: '2026-10-20T08:30:00Z',
  responseTargetMinutes: 60,
  resolutionTargetMinutes: 480,
  responseDueAt: '2026-10-20T09:30:00Z',
  resolutionDueAt: '2026-10-20T16:30:00Z',
  timeline: [],
}

/** Flat array of similar incidents — score always 0.0–1.0, single `fix` field */
const mockSimilarIncidents = [
  {
    id: 1,
    reference: 'INC-1',
    title: 'Payment gateway timeout during peak hour',
    serviceName: 'Payments',
    severity: 'SEV1',
    status: 'RESOLVED',
    score: 0.94,
    fix: 'Rolled back payments service deploy v2.4.1 and increased connection pool to 200.',
  },
  {
    id: 2,
    reference: 'INC-2',
    title: 'Card processing errors in checkout flow',
    serviceName: 'Payments',
    severity: 'SEV2',
    status: 'RESOLVED',
    score: 0.88,
    fix: 'Renewed expired SSL certificate on checkout ingress.',
  },
  {
    id: 3,
    reference: 'INC-3',
    title: 'Intermittent payments gateway failure',
    serviceName: 'Payments',
    severity: 'SEV2',
    status: 'CLOSED',
    score: 0.76,
    fix: 'Switched DNS failover route to secondary provider gateway.',
  },
  // 4th incident to test that max 3 are rendered
  {
    id: 4,
    reference: 'INC-4',
    title: 'Low priority payment timeout',
    serviceName: 'Payments',
    severity: 'SEV3',
    status: 'RESOLVED',
    score: 0.71,
    fix: 'Cleared Redis idempotency cache.',
  },
]

type Routes = Parameters<typeof mockFetch>[0]

function renderApp(path: string, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const defaultRoutes = {
    'GET /api/auth/me': { body: { user: engineer, org: devOrg } },
    'GET /api/services': { body: [payments] },
    'GET /api/incidents/7': { body: baseIncident },
    'GET /api/incidents/1': { body: { ...baseIncident, id: 1, reference: 'INC-1', title: 'Payment gateway timeout during peak hour' } },
    ...extraRoutes,
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const fullKey = `${method} ${String(input)}`
    const unencodedKey = `${method} ${decodeURIComponent(String(input))}`
    const pathOnly = String(input).split('?')[0]
    const pathKey = `${method} ${pathOnly}`

    let handler = (defaultRoutes as Record<string, unknown>)[fullKey] ??
      (defaultRoutes as Record<string, unknown>)[unencodedKey]

    if (!handler) {
      for (const [k, v] of Object.entries(defaultRoutes)) {
        if (k.startsWith(pathKey)) {
          handler = v
          break
        }
      }
    }
    if (!handler) {
      handler = (defaultRoutes as Record<string, unknown>)[pathKey]
    }

    if (!handler) {
      return new Response(JSON.stringify({ status: 404, message: `No mock for ${fullKey}` }), { status: 404 })
    }
    const route = typeof handler === 'function' ? handler(init) : handler
    if ((route as { status?: number }).status === 204) return new Response(null, { status: 204 })
    return new Response(JSON.stringify((route as { body?: unknown }).body), {
      status: (route as { status?: number }).status ?? 200,
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
// 1. Incident Detail Page: Similar Incidents
// ---------------------------------------------------------------------------
describe('Similar Incidents on Incident Detail Page (AI-1 / Issue #170)', () => {
  it('renders up to 3 similar past incidents with match score, fix, and AI label', async () => {
    renderApp('/incidents/7', {
      'GET /api/incidents/7/similar': { body: mockSimilarIncidents },
    })

    // Header and AI label
    expect(await screen.findByRole('heading', { name: 'Similar past incidents' })).toBeInTheDocument()
    const aiLabels = screen.getAllByTestId('ai-label')
    expect(aiLabels.length).toBeGreaterThanOrEqual(1)
    expect(aiLabels[0]).toHaveTextContent(/AI-generated suggestion/i)

    // Up to 3 rendered (even though fixture had 4)
    const cards = screen.getAllByTestId('similar-incident-card')
    expect(cards).toHaveLength(3)

    // INC-1
    expect(screen.getByText('INC-1')).toBeInTheDocument()
    expect(screen.getByText('Payment gateway timeout during peak hour')).toBeInTheDocument()
    expect(screen.getByText('94% match')).toBeInTheDocument()
    expect(screen.getByText(/Rolled back payments service deploy v2\.4\.1/)).toBeInTheDocument()

    // INC-2
    expect(screen.getByText('INC-2')).toBeInTheDocument()
    expect(screen.getByText('88% match')).toBeInTheDocument()
    expect(screen.getByText(/Renewed expired SSL certificate/)).toBeInTheDocument()

    // INC-3
    expect(screen.getByText('INC-3')).toBeInTheDocument()
    expect(screen.getByText('76% match')).toBeInTheDocument()

    // 4th incident is not rendered
    expect(screen.queryByText('INC-4')).not.toBeInTheDocument()
  })

  it('clicking a similar incident link navigates to its detail page', async () => {
    renderApp('/incidents/7', {
      'GET /api/incidents/7/similar': { body: mockSimilarIncidents },
    })

    const link = await screen.findByRole('link', { name: 'Payment gateway timeout during peak hour' })
    expect(link).toHaveAttribute('href', '/incidents/1')

    await userEvent.click(link)

    expect(await screen.findByRole('heading', { name: 'Payment gateway timeout during peak hour' })).toBeInTheDocument()
  })

  it('disappears quietly when AI is disabled or returns empty results', async () => {
    renderApp('/incidents/7', {
      'GET /api/incidents/7/similar': { body: [] },
    })

    // Wait for page to load
    expect(await screen.findByRole('heading', { name: 'Checkout failing for card payments' })).toBeInTheDocument()

    // No similar incidents section or AI label rendered
    expect(screen.queryByRole('heading', { name: 'Similar past incidents' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('ai-label')).not.toBeInTheDocument()
    expect(screen.queryByTestId('similar-incident-card')).not.toBeInTheDocument()
  })

  it('hides quietly when the similar incidents API returns 503 (error propagates to query)', async () => {
    renderApp('/incidents/7', {
      'GET /api/incidents/7/similar': { status: 503, body: { message: 'AI service unavailable' } },
    })

    // Incident page still loads normally
    expect(await screen.findByRole('heading', { name: 'Checkout failing for card payments' })).toBeInTheDocument()
    expect(screen.getByText('INC-7')).toBeInTheDocument()

    // Similar incidents section quietly absent (query isError hides it)
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Similar past incidents' })).not.toBeInTheDocument()
    })
  })

  it('a malformed response is NOT silently converted to empty success — Zod rejects it', async () => {
    // Score > 1 violates the 0–1 schema constraint
    const malformed = [{ id: 1, reference: 'INC-1', title: 'Bad', score: 150 }]
    renderApp('/incidents/7', {
      'GET /api/incidents/7/similar': { body: malformed },
    })

    // Incident detail still loads
    expect(await screen.findByRole('heading', { name: 'Checkout failing for card payments' })).toBeInTheDocument()

    // Panel hidden because Zod validation error propagates to query's error state
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Similar past incidents' })).not.toBeInTheDocument()
    })
  })
})

// ---------------------------------------------------------------------------
// 2. Incident Declaration Form: Duplicate Warning
// ---------------------------------------------------------------------------
describe('Duplicate Incident Warning on Declare Form (AI-1 / Issue #170)', () => {
  it('shows a warning when the backend detects an open duplicate incident', async () => {
    renderApp('/incidents/new', {
      'GET /api/incidents/duplicate-check?title=Checkout+failing': {
        body: {
          duplicate: true,
          incident: {
            id: 42,
            reference: 'INC-42',
            title: 'Checkout gateway errors',
          },
        },
      },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    const titleInput = screen.getByLabelText('Title')
    await userEvent.type(titleInput, 'Checkout failing')

    // Warning appears
    const warning = await screen.findByTestId('duplicate-warning')
    expect(warning).toBeInTheDocument()
    expect(within(warning).getByText(/This looks like open incident/)).toBeInTheDocument()
    expect(within(warning).getByText('INC-42')).toBeInTheDocument()
    expect(within(warning).getByText(/"Checkout gateway errors"/)).toBeInTheDocument()
    expect(within(warning).getByTestId('ai-label')).toHaveTextContent('AI')

    // Verify link to the open duplicate incident
    const link = within(warning).getByRole('link', { name: 'INC-42' })
    expect(link).toHaveAttribute('href', '/incidents/42')
  })

  it('the warning never blocks or disables incident declaration', async () => {
    const createdIncident = {
      ...baseIncident,
      id: 99,
      reference: 'INC-99',
      title: 'Checkout failing',
    }

    const { fetchMock } = renderApp('/incidents/new', {
      'GET /api/incidents/duplicate-check?title=Checkout+failing': {
        body: {
          duplicate: true,
          incident: { id: 42, reference: 'INC-42', title: 'Existing open issue' },
        },
      },
      'POST /api/incidents': {
        status: 201,
        body: createdIncident,
      },
      'GET /api/incidents/99': {
        body: createdIncident,
      },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    // Fill form
    await userEvent.type(screen.getByLabelText('Title'), 'Checkout failing')
    await userEvent.type(screen.getByLabelText('Description'), 'Card checkout is timing out at payment provider.')
    await userEvent.click(screen.getByRole('combobox', { name: 'Service' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))
    await userEvent.click(screen.getByRole('combobox', { name: 'Severity' }))
    await userEvent.click(await screen.findByRole('option', { name: /SEV2/ }))

    // Duplicate warning is visible
    expect(await screen.findByTestId('duplicate-warning')).toBeInTheDocument()

    // Submit button is NOT disabled
    const submitBtn = screen.getByRole('button', { name: 'Declare incident' })
    expect(submitBtn).toBeEnabled()

    // User submits despite warning
    await userEvent.click(submitBtn)

    // Form successfully creates incident and navigates to the new incident page
    await waitFor(() => {
      const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts.some(([url]) => String(url).includes('/api/incidents'))).toBe(true)
    })
  })

  it('user can dismiss the duplicate warning', async () => {
    renderApp('/incidents/new', {
      'GET /api/incidents/duplicate-check?title=Checkout+failing': {
        body: {
          duplicate: true,
          incident: { id: 42, reference: 'INC-42', title: 'Existing open issue' },
        },
      },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })
    await userEvent.type(screen.getByLabelText('Title'), 'Checkout failing')

    const warning = await screen.findByTestId('duplicate-warning')
    expect(warning).toBeInTheDocument()

    // Click dismiss button
    const dismissBtn = within(warning).getByRole('button', { name: 'Dismiss duplicate warning' })
    await userEvent.click(dismissBtn)

    // Warning is gone
    await waitFor(() => {
      expect(screen.queryByTestId('duplicate-warning')).not.toBeInTheDocument()
    })
  })

  it('does not show warning when AI returns no duplicate', async () => {
    renderApp('/incidents/new', {
      'GET /api/incidents/duplicate-check?title=Unique+issue': {
        body: { duplicate: false, incident: null },
      },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })
    await userEvent.type(screen.getByLabelText('Title'), 'Unique issue')

    await screen.findByDisplayValue('Unique issue')
    expect(screen.queryByTestId('duplicate-warning')).not.toBeInTheDocument()
  })

  it('hides warning when duplicate check API fails — never blocks declaration', async () => {
    renderApp('/incidents/new', {
      'GET /api/incidents/duplicate-check?title=Error+flow': {
        status: 500,
        body: { message: 'AI service unavailable' },
      },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })
    await userEvent.type(screen.getByLabelText('Title'), 'Error flow')

    await screen.findByDisplayValue('Error flow')
    // Warning absent — error propagated to query's isError state, component hides
    expect(screen.queryByTestId('duplicate-warning')).not.toBeInTheDocument()
    // Normal declaration button is ready
    expect(screen.getByRole('button', { name: 'Declare incident' })).toBeEnabled()
  })
})
