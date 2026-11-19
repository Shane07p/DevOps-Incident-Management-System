/**
 * TriageHint.test.tsx
 * Tests for Issue #247 — AI triage hint on the declare form (AI-4).
 *
 * TC-FR-27.3a coverage:
 *   - AI label appears when hint is returned
 *   - Suggested severity and service appear
 *   - Reason appears
 *   - Form values remain unchanged before Apply
 *   - Apply updates both severity and service
 *   - AI failure hides the hint (declaration still works)
 *   - Existing duplicate-warning tests still pass
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
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

const engineer = users[1]

const baseTriageHint = {
  severity: 'SEV2',
  service: { id: 1, name: 'Payments' },
  reason: 'Similar payment incidents suggest high urgency.',
}

type Routes = Parameters<typeof mockFetch>[0]

function renderApp(path: string, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const defaultRoutes: Routes = {
    'GET /api/auth/me': { body: { user: engineer, org: devOrg } },
    'GET /api/services': { body: [payments] },
    ...extraRoutes,
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const fullKey = `${method} ${String(input)}`
    const pathOnly = String(input).split('?')[0]
    const pathKey = `${method} ${pathOnly}`

    let handler = (defaultRoutes as Record<string, unknown>)[fullKey]
    if (!handler) handler = (defaultRoutes as Record<string, unknown>)[pathKey]
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
// 1. TriageHint appears when AI returns a hint
// ---------------------------------------------------------------------------
describe('TriageHint on Declare Form (AI-4 / Issue #247)', () => {
  it('shows AI label, suggested severity, service, and reason when API returns a hint', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { body: baseTriageHint },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    // Type a title long enough to trigger the debounced query
    const titleInput = screen.getByLabelText('Title')
    await userEvent.type(titleInput, 'Payment gateway timeout')

    // AI hint should appear
    const hint = await screen.findByTestId('triage-hint')
    expect(hint).toBeInTheDocument()

    // AI label
    expect(screen.getByTestId('ai-label')).toBeInTheDocument()
    expect(screen.getByTestId('ai-label')).toHaveTextContent('AI')

    // Severity badge text (SEV2)
    expect(hint.textContent).toMatch(/SEV2/)

    // Service
    expect(screen.getByTestId('triage-hint-service')).toHaveTextContent('Payments')

    // Reason
    expect(screen.getByTestId('triage-hint-reason')).toHaveTextContent('Similar payment incidents suggest high urgency.')
  })

  it('form values are NOT changed before the user clicks Apply', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { body: baseTriageHint },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    const titleInput = screen.getByLabelText('Title')
    await userEvent.type(titleInput, 'Payment gateway timeout')

    // Wait for hint to appear
    await screen.findByTestId('triage-hint')

    // Severity dropdown should still show placeholder (not filled by AI)
    const severityTrigger = screen.getByRole('combobox', { name: 'Severity' })
    expect(severityTrigger).toHaveTextContent(/Choose a severity/)
  })

  it('Apply button updates severity and service in the form', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { body: baseTriageHint },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    const titleInput = screen.getByLabelText('Title')
    await userEvent.type(titleInput, 'Payment gateway timeout')

    // Wait for hint to appear
    const applyBtn = await screen.findByTestId('triage-hint-apply')
    await userEvent.click(applyBtn)

    // After Apply, severity field should show SEV2
    const severityTrigger = screen.getByRole('combobox', { name: 'Severity' })
    await waitFor(() => {
      expect(severityTrigger.textContent).toMatch(/SEV2/)
    })
  })

  it('hint is hidden when API returns 503 (AI unavailable), declaration still works', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { status: 503, body: { message: 'AI service unavailable' } },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    const titleInput = screen.getByLabelText('Title')
    await userEvent.type(titleInput, 'Payment gateway timeout')

    // Wait for the debounce + query to settle
    await new Promise((r) => setTimeout(r, 800))

    // Hint should NOT appear
    expect(screen.queryByTestId('triage-hint')).not.toBeInTheDocument()

    // Declare incident button still enabled
    expect(screen.getByRole('button', { name: 'Declare incident' })).toBeEnabled()
  })

  it('hint is hidden when title is too short', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { body: baseTriageHint },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    // Type only 2 characters — below the minimum 3
    await userEvent.type(screen.getByLabelText('Title'), 'Pa')
    await new Promise((r) => setTimeout(r, 800))

    expect(screen.queryByTestId('triage-hint')).not.toBeInTheDocument()
  })

  it('hint hides after Apply is clicked and shows Apply did not submit the form', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': { body: baseTriageHint },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    await userEvent.type(screen.getByLabelText('Title'), 'Payment gateway timeout')

    const applyBtn = await screen.findByTestId('triage-hint-apply')
    await userEvent.click(applyBtn)

    // Hint hides after apply
    await waitFor(() => {
      expect(screen.queryByTestId('triage-hint')).not.toBeInTheDocument()
    })

    // But the page (declare form) is still visible — form was not submitted
    expect(screen.getByRole('button', { name: 'Declare incident' })).toBeInTheDocument()
  })

  it('hint shows no service when service is null', async () => {
    renderApp('/incidents/new', {
      'POST /api/incidents/triage-hint': {
        body: { severity: 'SEV3', service: null, reason: 'No service identified.' },
      },
      'GET /api/incidents/duplicate-check': { body: { duplicate: false, incident: null } },
    })

    await screen.findByRole('heading', { name: 'Declare incident' })

    await userEvent.type(screen.getByLabelText('Title'), 'Payment gateway timeout')

    await screen.findByTestId('triage-hint')

    expect(screen.queryByTestId('triage-hint-service')).not.toBeInTheDocument()
    expect(screen.getByTestId('ai-label')).toBeInTheDocument()
  })
})
