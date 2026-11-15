import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

const [admin, engineer] = users
const search = { ...payments, id: 2, name: 'Search', description: 'Product search', owner: { id: 2, displayName: 'Dev Engineer' }, criticality: 'MEDIUM' }

const row = (overrides: Record<string, unknown>) => ({
  serviceName: 'Payments',
  severity: 'SEV2',
  status: 'OPEN',
  createdAt: '2026-10-20T08:00:00Z',
  responseDueAt: '2026-10-20T09:30:00Z',
  resolutionDueAt: '2026-10-20T16:30:00Z',
  version: 0,
  assignee: null,
  acknowledgedAt: null,
  resolvedAt: null,
  ...overrides,
})

const incidents = [
  row({ id: 7, reference: 'INC-7', title: 'Refunds slow' }),
  row({ id: 8, reference: 'INC-8', title: 'Card payments failing', severity: 'SEV1', status: 'INVESTIGATING' }),
  row({ id: 5, reference: 'INC-5', title: 'Old outage', status: 'RESOLVED', createdAt: '2026-10-10T08:00:00Z' }),
]

function renderAt(path: string, user: unknown) {
  vi.useFakeTimers({ toFake: ['Date'] })
  // 14:20 IST on 20 Oct.
  vi.setSystemTime(new Date('2026-10-20T08:50:00Z'))
  writeToken('test-token')
  mockFetch({
    'GET /api/auth/me': { body: { user, org: devOrg } },
    'GET /api/services': { body: [payments, search] },
    'GET /api/incidents': { body: incidents },
    'GET /api/service-map': {
      body: {
        nodes: [
          { id: 1, name: 'Payments', criticality: 'CRITICAL', health: 'DEGRADED' },
          { id: 2, name: 'Search', criticality: 'MEDIUM', health: 'OPERATIONAL' },
        ],
        edges: [],
      },
    },
    'GET /api/services/1/on-call': {
      body: {
        serviceId: 1,
        fallbackContactId: 1,
        fallbackContactName: 'Dev Admin',
        shifts: [
          { id: 1, responderId: 2, responderName: 'Dev Engineer', startTime: '2026-10-20T03:30:00Z', endTime: '2026-10-20T11:30:00Z' },
          { id: 2, responderId: 1, responderName: 'Dev Admin', startTime: '2026-10-20T11:30:00Z', endTime: '2026-10-20T17:30:00Z' },
        ],
      },
    },
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <TooltipProvider>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </TooltipProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  clearToken()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('service catalog', () => {
  it('shows each card with its health, its most severe open incident, owner and criticality', async () => {
    renderAt('/services', engineer)

    const card = (await screen.findByRole('link', { name: 'Payments' })).closest('li')!
    expect(within(card).getByText('Degraded')).toBeInTheDocument()
    expect(await within(card).findByText('INC-8 · SEV1 · Investigating · +1 more')).toBeInTheDocument()
    expect(within(card).getByText('Dev Admin')).toBeInTheDocument()
    expect(within(card).getByText('critical')).toBeInTheDocument()
    const other = screen.getByRole('link', { name: 'Search' }).closest('li')!
    expect(within(other).getByText('No open incidents')).toBeInTheDocument()
  })

  it('finds a service by name, and opens it from its card', async () => {
    const router = renderAt('/services', engineer)

    await userEvent.type(await screen.findByLabelText('Find a service'), 'sear')
    expect(screen.queryByRole('link', { name: 'Payments' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Search' }))

    expect(router.state.location.pathname).toBe('/services/2')
    expect(await screen.findByRole('heading', { level: 1, name: 'Search' })).toBeInTheDocument()
  })
})

describe('service page', () => {
  it('splits open and past incidents, names who is on call, and lists the SLA targets', async () => {
    renderAt('/services/1', engineer)

    const open = await screen.findByRole('region', { name: 'Open incidents' })
    const rows = within(open).getAllByRole('row').slice(1)
    expect(rows.map((tableRow) => within(tableRow).getByRole('link').textContent)).toEqual(['Card payments failing', 'Refunds slow'])
    expect(within(screen.getByRole('region', { name: 'Past incidents' })).getByText('Old outage')).toBeInTheDocument()

    const onCall = screen.getByRole('region', { name: 'On call' })
    expect(await within(onCall).findByText('Dev Engineer')).toBeInTheDocument()
    expect(within(onCall).getByText(/then Dev Admin from 20 Oct, 17:00 IST/)).toBeInTheDocument()

    const targets = screen.getByRole('region', { name: 'Service-level targets' })
    const sev1 = within(targets).getByText('SEV1').closest('tr')!
    expect(within(sev1).getByText('15 min')).toBeInTheDocument()
    expect(within(sev1).getByText('4 h')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Configure service' })).not.toBeInTheDocument()
  })

  it('lets an Admin configure the service in the edit form', async () => {
    renderAt('/services/1', admin)

    await userEvent.click(await screen.findByRole('button', { name: 'Configure service' }))
    expect(await screen.findByRole('dialog', { name: 'Edit Payments' })).toBeInTheDocument()
  })

  it('says so when the service does not exist', async () => {
    renderAt('/services/99', engineer)

    expect(await screen.findByRole('heading', { level: 1, name: 'Service not found' })).toBeInTheDocument()
  })
})
