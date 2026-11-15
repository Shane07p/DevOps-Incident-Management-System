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
import { devOrg, mockFetch } from '@/test/fetchMock'

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

// Out of order on purpose: the page must still show the newest first.
// INC-2 and INC-3 are assigned to the viewer (id 3); INC-1 to someone else.
const alerts = [
  { id: 6, source: 'demo-shop', serviceName: 'Checkout', incidentId: 6, incidentReference: 'INC-1', assigneeId: 2, status: 'ATTACHED', correlationKey: 'checkout-latency', createdAt: '2026-10-28T04:00:00Z', count: 1 },
  { id: 9, source: 'grafana', serviceName: 'Payments', incidentId: 7, incidentReference: 'INC-2', assigneeId: 3, status: 'ATTACHED', correlationKey: 'payments-5xx', createdAt: '2026-10-28T06:00:00Z', count: 2 },
  { id: 8, source: 'uptime-robot', serviceName: 'Checkout', incidentId: 8, incidentReference: 'INC-3', assigneeId: 3, status: 'NEW', correlationKey: null, createdAt: '2026-10-28T05:30:00Z', count: 1 },
]

function renderInbox(path = '/alerts', body: unknown = alerts) {
  writeToken('test-token')
  mockFetch({ 'GET /api/auth/me': { body: { user: viewer, org: devOrg } }, 'GET /api/alerts': { body } })
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
  return router
}

const sources = () => screen.getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[1].textContent)

// The cell of a row under the named column, so a column added or moved cannot point the check elsewhere.
function cellUnder(row: HTMLElement, column: string) {
  const headers = screen.getAllByRole('columnheader').map((header) => header.textContent)
  const index = headers.indexOf(column)
  expect(index).toBeGreaterThanOrEqual(0)
  return within(row).getAllByRole('cell')[index]
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('AlertsPage', () => {
  it('shows each alert newest first, with its source, service, count, and linked incident', async () => {
    renderInbox()

    await screen.findByText('grafana')
    expect(sources()).toEqual(['grafana', 'uptime-robot', 'demo-shop'])
    const newest = screen.getAllByRole('row')[1]
    expect(newest).toHaveTextContent('28 Oct, 11:30 IST')
    expect(newest).toHaveTextContent('Payments')
    expect(newest).toHaveTextContent('payments-5xx')
    // The org's own number, not the incident's id (D48).
    expect(within(newest).getByRole('link', { name: 'INC-2' })).toHaveAttribute('href', '/incidents/7')
    // Two alerts share INC-2, this one included.
    expect(cellUnder(newest, 'Alerts')).toHaveTextContent('2')
  })

  it('shows only the alerts that opened an incident under Opened, and remembers the filter in the URL', async () => {
    const router = renderInbox()

    await userEvent.click(await screen.findByRole('button', { name: /^Opened/ }))

    expect(sources()).toEqual(['uptime-robot'])
    expect(screen.getByRole('button', { name: /^Opened/ })).toHaveAttribute('aria-pressed', 'true')
    expect(router.state.location.search).toBe('?status=new')
  })

  it('shows only the alerts grouped into an open incident under Grouped, with a count on each filter', async () => {
    renderInbox()

    await userEvent.click(await screen.findByRole('button', { name: /^Grouped/ }))

    expect(sources()).toEqual(['grafana', 'demo-shop'])
    expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('3')
    expect(screen.getByRole('button', { name: /^Opened/ })).toHaveTextContent('1')
    expect(screen.getByRole('button', { name: /^Grouped/ })).toHaveTextContent('2')
  })

  it('opens on the filter named in the URL', async () => {
    renderInbox('/alerts?status=attached')

    await screen.findByText('grafana')
    expect(sources()).toEqual(['grafana', 'demo-shop'])
  })

  it('shows only alerts whose incident is assigned to the viewer under Routed to me', async () => {
    const router = renderInbox()

    await userEvent.click(await screen.findByRole('button', { name: 'Routed to me' }))

    expect(sources()).toEqual(['grafana', 'uptime-robot'])
    expect(screen.getByRole('button', { name: 'Routed to me' })).toHaveAttribute('aria-pressed', 'true')
    expect(router.state.location.search).toBe('?routed=me')
    // The status counts follow what is routed to the viewer.
    expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('2')
    expect(screen.getByRole('button', { name: /^Grouped/ })).toHaveTextContent('1')
  })

  it('combines Routed to me with a status filter, and keeps both in the URL', async () => {
    const router = renderInbox('/alerts?routed=me')

    await userEvent.click(await screen.findByRole('button', { name: /^Opened/ }))

    expect(sources()).toEqual(['uptime-robot'])
    expect(router.state.location.search).toBe('?routed=me&status=new')

    await userEvent.click(screen.getByRole('button', { name: 'Routed to me' }))

    expect(router.state.location.search).toBe('?status=new')
    expect(sources()).toEqual(['uptime-robot'])
  })

  it('says so when nothing is routed to the viewer', async () => {
    renderInbox('/alerts?routed=me', alerts.filter((alert) => alert.assigneeId !== 3))

    expect(await screen.findByText('No alerts routed to you.')).toBeInTheDocument()
  })

  it('says so when a filter has no alerts', async () => {
    renderInbox('/alerts?status=new', alerts.filter((alert) => alert.status !== 'NEW'))

    expect(await screen.findByText('No alert opened an incident.')).toBeInTheDocument()
  })

  it("counts today's alerts, and says how each one arrived", async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // 12:00 IST on 28 Oct: the demo-shop alert came at 09:30 IST, the others later.
    vi.setSystemTime(new Date('2026-10-28T06:30:00Z'))
    renderInbox()

    const today = await screen.findByRole('region', { name: 'Alerts today' })
    expect(today).toHaveTextContent('Received today3from demo-shop, grafana and uptime-robot')
    expect(today).toHaveTextContent('Opened an incident1')
    expect(today).toHaveTextContent('Grouped2')
    const [newest, second] = screen.getAllByRole('row').slice(1)
    expect(cellUnder(newest, 'How it arrived')).toHaveTextContent('Grouped')
    expect(cellUnder(second, 'How it arrived')).toHaveTextContent('Opened the incident')
    expect(cellUnder(second, 'Alert')).toHaveTextContent('no correlation key')
    vi.useRealTimers()
  })
})
