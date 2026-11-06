import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' },
}

// Out of order on purpose: the page must still show the newest first.
const summaries = [
  { id: 5, reference: 'INC-5', title: 'Slow search', serviceName: 'Payments', severity: 'SEV3', status: 'RESOLVED', createdAt: '2026-10-18T09:00:00Z' },
  { id: 7, reference: 'INC-7', title: 'Checkout failing', serviceName: 'Payments', severity: 'SEV2', status: 'OPEN', createdAt: '2026-10-20T08:30:00Z' },
  { id: 6, reference: 'INC-6', title: 'Refunds delayed', serviceName: 'Payments', severity: 'SEV1', status: 'INVESTIGATING', createdAt: '2026-10-19T12:00:00Z' },
]

// Declared at 14:00 IST; the SEV2 targets (60 and 480 minutes) were copied from Payments.
const checkoutFailing = {
  id: 7,
  reference: 'INC-7',
  title: 'Checkout failing',
  description: 'Card payments fail at the last step.',
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
  timeline: [
    { id: 3, eventType: 'INCIDENT_CREATED', actor: 'Dev Engineer', details: 'Reported as SEV2 on Payments', recordedAt: '2026-10-20T08:30:00Z', correctsEntryId: null },
  ],
}

type Routes = Parameters<typeof mockFetch>[0]

// Runs the app's real route table, so the role guards are the ones users get.
function renderApp(role: Role, path: string, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: signedIn[role], org: devOrg } },
    'GET /api/incidents': { body: summaries },
    'GET /api/incidents/7': { body: checkoutFailing },
    'GET /api/services': { body: [payments] },
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

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})


describe('Incident suggested runbooks', () => {
  it('TC-FR-25.2a: shows service runbooks with steps in order', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7', {
      'GET /api/services/1/runbooks': {
        body: [
          {
            id: 10,
            title: 'Payments recovery',
            steps: [
              'Check payment gateway',
              'Restart payment worker',
              'Verify successful payments',
            ],
          },
        ],
      },
    })

    expect(await screen.findByRole('heading', {
      name: 'Suggested runbooks',
    })).toBeInTheDocument()

    expect(await screen.findByRole('heading', {
      name: 'Payments recovery',
    })).toBeInTheDocument()

    const steps = screen.getAllByRole('listitem')
      .filter((item) =>
        ['Check payment gateway', 'Restart payment worker', 'Verify successful payments']
          .includes(item.textContent?.trim() ?? ''),
      )
      .map((item) => item.textContent?.trim())

    expect(steps).toEqual([
      'Check payment gateway',
      'Restart payment worker',
      'Verify successful payments',
    ])

    expect(fetchMock).toHaveBeenCalled()
  })
})
