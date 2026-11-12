import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { routes } from '@/router'
import { mockFetch } from '@/test/fetchMock'

/** 90 UTC days, oldest first, ending 12 Nov 2026; `overrides` maps a day's index to its uptime. */
function days(overrides: Record<number, number | null> = {}) {
  return Array.from({ length: 90 }, (_, index) => {
    const date = new Date(Date.UTC(2026, 10, 12) - (89 - index) * 86_400_000).toISOString().slice(0, 10)
    return { date, uptimePercent: index in overrides ? overrides[index] : 100 }
  })
}

const noChecks = Object.fromEntries(Array.from({ length: 90 }, (_, index) => [index, null]))

const operational = { id: 1, name: 'Card payments', health: 'OPERATIONAL', uptimePercent: 100, uptimeDays: days() }
const degraded = { id: 2, name: 'Search', health: 'DEGRADED', uptimePercent: 99.5, uptimeDays: days({ 89: 98.6 }) }
const down = { id: 3, name: 'Checkout', health: 'DOWN', uptimePercent: 97.25, uptimeDays: days({ 89: 40 }) }
const unchecked = { id: 4, name: 'Login', health: 'UNKNOWN', uptimePercent: null, uptimeDays: days(noChecks) }

const quiet = { orgName: 'Acme', services: [operational], activeIncidents: [], resolvedIncidents: [] }

function renderStatus(path: string, routesByKey: Parameters<typeof mockFetch>[0] = {}) {
  const fetchMock = mockFetch(routesByKey)
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <ThemeProvider>
      <QueryClientProvider client={client}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return fetchMock
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Public status page', () => {
  // TC-FR-21.1c
  it('shows each service under its public name with its health and 90-day uptime', async () => {
    renderStatus('/status/acme', {
      'GET /api/public/status/acme': { body: { ...quiet, services: [operational, degraded, down, unchecked] } },
    })

    const section = (await screen.findByRole('heading', { name: 'Components' })).closest('section')!
    expect(within(section).getByText('Operational')).toBeInTheDocument()
    expect(within(section).getByText('Degraded')).toBeInTheDocument()
    expect(within(section).getByText('Major outage')).toBeInTheDocument()
    expect(within(section).getByText('No data yet')).toBeInTheDocument()
    expect(within(section).getByRole('img', { name: 'Card payments: no incidents in the last 90 days, 100% uptime' })).toBeInTheDocument()
    expect(within(section).getByRole('img', { name: 'Checkout: 1 days with incidents in the last 90 days, 97.25% uptime' })).toBeInTheDocument()
    // A service with no checks has no uptime figure.
    expect(within(section).getByRole('img', { name: 'Login: no incidents in the last 90 days' })).toBeInTheDocument()
  })

  it.each([
    ['All systems operational', [operational], 'Acme service status'],
    ['Partial outage', [operational, degraded], 'Search is affected.'],
    ['Major outage', [degraded, down], 'Search, Checkout are affected.'],
  ])('sums the page up as %s', async (title, services, text) => {
    renderStatus('/status/acme', { 'GET /api/public/status/acme': { body: { ...quiet, services } } })

    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
    expect(screen.getByText(text)).toBeInTheDocument()
  })

  it('shows an active incident with its updates newest first, and past incidents', async () => {
    renderStatus('/status/acme', {
      'GET /api/public/status/acme': {
        body: {
          ...quiet,
          activeIncidents: [
            {
              id: 7,
              serviceName: 'Card payments',
              status: 'IDENTIFIED',
              updates: [
                { id: 12, status: 'IDENTIFIED', message: 'We found the cause and are rolling it back.', createdAt: '2026-11-12T09:40:00Z' },
                { id: 11, status: 'INVESTIGATING', message: 'Some card payments are failing.', createdAt: '2026-11-12T08:30:00Z' },
              ],
              resolvedAt: null,
            },
          ],
          resolvedIncidents: [
            {
              id: 5,
              serviceName: 'Search',
              status: 'RESOLVED',
              updates: [{ id: 9, status: 'RESOLVED', message: 'Search is back to normal.', createdAt: '2026-11-10T06:00:00Z' }],
              resolvedAt: '2026-11-10T06:00:00Z',
            },
          ],
        },
      },
    })

    // An open incident means its service is affected, even while its monitor passes.
    expect(await screen.findByRole('heading', { name: 'Partial outage' })).toBeInTheDocument()
    const active = screen.getByRole('heading', { name: 'Active incident' }).closest('section')!
    expect(within(active).getByText(/^Started 12 Nov.*14:00.*IST$/)).toBeInTheDocument()
    const updates = within(active).getAllByRole('listitem')
    expect(updates[0]).toHaveTextContent('We found the cause')
    expect(updates[1]).toHaveTextContent('Some card payments are failing.')

    const past = screen.getByRole('heading', { name: 'Past incidents' }).closest('section')!
    expect(within(past).getByText('Search is back to normal.')).toBeInTheDocument()
    expect(within(past).getByText(/^Resolved 10 Nov.*11:30.*IST$/)).toBeInTheDocument()
  })

  it('reads the page without a login and sends no token', async () => {
    const fetchMock = renderStatus('/status/acme', { 'GET /api/public/status/acme': { body: quiet } })

    expect(await screen.findByRole('heading', { name: 'All systems operational' })).toBeInTheDocument()
    const [, init] = fetchMock.mock.calls.find(([url]) => url === '/api/public/status/acme')!
    expect(init?.headers).not.toHaveProperty('Authorization')
  })

  // TC-FR-21.1d
  it('says so when no org uses the address', async () => {
    renderStatus('/status/nobody', {
      'GET /api/public/status/nobody': { status: 404, body: { status: 404, message: 'Status page not found', errors: {} } },
    })

    expect(await screen.findByRole('heading', { name: 'Status page not found' })).toBeInTheDocument()
  })

  it('explains the address when it has no org in it', async () => {
    renderStatus('/status')

    expect(await screen.findByRole('heading', { name: 'Which status page?' })).toBeInTheDocument()
  })
})
