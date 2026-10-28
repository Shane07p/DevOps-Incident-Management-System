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
import { mockFetch } from '@/test/fetchMock'

const viewer = { id: 3, username: 'viewer', displayName: 'Dev Viewer', role: 'VIEWER' }

// Out of order on purpose: the page must still show the newest first.
const alerts = [
  { id: 6, source: 'demo-shop', serviceName: 'Checkout', incidentId: 6, status: 'ATTACHED', correlationKey: 'checkout-latency', createdAt: '2026-10-28T04:00:00Z' },
  { id: 9, source: 'grafana', serviceName: 'Payments', incidentId: 7, status: 'ATTACHED', correlationKey: 'payments-5xx', createdAt: '2026-10-28T06:00:00Z' },
  { id: 8, source: 'uptime-robot', serviceName: 'Checkout', incidentId: 8, status: 'NEW', correlationKey: null, createdAt: '2026-10-28T05:30:00Z' },
]

function renderInbox(path = '/alerts', body: unknown = alerts) {
  writeToken('test-token')
  mockFetch({ 'GET /api/auth/me': { body: viewer }, 'GET /api/alerts': { body } })
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

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('AlertsPage', () => {
  it('shows each alert newest first, with its source, service, and linked incident', async () => {
    renderInbox()

    await screen.findByText('grafana')
    expect(sources()).toEqual(['grafana', 'uptime-robot', 'demo-shop'])
    const newest = screen.getAllByRole('row')[1]
    expect(newest).toHaveTextContent('28 Oct, 11:30 IST')
    expect(newest).toHaveTextContent('Payments')
    expect(newest).toHaveTextContent('payments-5xx')
    expect(within(newest).getByRole('link', { name: 'INC-7' })).toHaveAttribute('href', '/incidents/7')
  })

  it('shows only new alerts under New, and remembers the filter in the URL', async () => {
    const router = renderInbox()

    await userEvent.click(await screen.findByRole('button', { name: /^New/ }))

    expect(sources()).toEqual(['uptime-robot'])
    expect(screen.getByRole('button', { name: /^New/ })).toHaveAttribute('aria-pressed', 'true')
    expect(router.state.location.search).toBe('?status=new')
  })

  it('shows only attached alerts under Attached, with a count on each filter', async () => {
    renderInbox()

    await userEvent.click(await screen.findByRole('button', { name: /^Attached/ }))

    expect(sources()).toEqual(['grafana', 'demo-shop'])
    expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('3')
    expect(screen.getByRole('button', { name: /^New/ })).toHaveTextContent('1')
    expect(screen.getByRole('button', { name: /^Attached/ })).toHaveTextContent('2')
  })

  it('opens on the filter named in the URL', async () => {
    renderInbox('/alerts?status=attached')

    await screen.findByText('grafana')
    expect(sources()).toEqual(['grafana', 'demo-shop'])
  })

  it('says so when a filter has no alerts', async () => {
    renderInbox('/alerts?status=new', alerts.filter((alert) => alert.status !== 'NEW'))

    expect(await screen.findByText('No new alerts.')).toBeInTheDocument()
  })
})
