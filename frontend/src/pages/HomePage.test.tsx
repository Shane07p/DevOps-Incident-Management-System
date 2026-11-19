import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { toast } from 'sonner'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, users } from '@/test/fetchMock'

const [, engineer] = users
const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

const row = (overrides: Record<string, unknown>) => ({
  serviceName: 'Payments',
  severity: 'SEV2',
  responseDueAt: '2026-10-20T09:30:00Z',
  resolutionDueAt: '2026-10-20T16:30:00Z',
  version: 0,
  assignee: null,
  acknowledgedAt: null,
  resolvedAt: null,
  ...overrides,
})

// INC-8 is the older of the two OPEN incidents, so it is waiting longest and comes first; its response is 5 min overdue.
const incidents = [
  row({ id: 7, reference: 'INC-7', title: 'Checkout failing', status: 'OPEN', createdAt: '2026-10-20T08:30:00Z', version: 4 }),
  row({ id: 8, reference: 'INC-8', title: 'Search typo', status: 'OPEN', severity: 'SEV4', createdAt: '2026-10-19T09:30:00Z', responseDueAt: '2026-10-20T08:45:00Z' }),
  row({
    id: 6,
    reference: 'INC-6',
    title: 'Refunds delayed',
    status: 'INVESTIGATING',
    severity: 'SEV1',
    createdAt: '2026-10-20T07:00:00Z',
    responseDueAt: '2026-10-20T07:15:00Z',
    resolutionDueAt: '2026-10-20T11:00:00Z',
    assignee: { id: 2, displayName: 'Dev Engineer' },
    acknowledgedAt: '2026-10-20T07:10:00Z',
  }),
  row({ id: 5, reference: 'INC-5', title: 'Slow search', status: 'RESOLVED', createdAt: '2026-10-18T09:00:00Z' }),
]

type Routes = Parameters<typeof mockFetch>[0]

function renderOverview(user: unknown, extra: Routes = {}) {
  vi.useFakeTimers({ toFake: ['Date'] })
  // 14:20 IST on 20 Oct.
  vi.setSystemTime(new Date('2026-10-20T08:50:00Z'))
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user, org: devOrg } },
    'GET /api/incidents': { body: incidents },
    ...extra,
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <TooltipProvider>
            <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/home'] })} />
          </TooltipProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return fetchMock
}

const waiting = () => screen.findByRole('region', { name: /Waiting for acknowledgement/ })
const listReads = (fetchMock: ReturnType<typeof mockFetch>) =>
  fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/incidents' && (init?.method ?? 'GET') === 'GET').length

afterEach(() => {
  clearToken()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Overview', () => {
  it('lists OPEN incidents oldest first, and the ones in progress with their Lead and both clocks', async () => {
    renderOverview(engineer)

    const queue = await waiting()
    expect(within(queue).getAllByRole('link').map((link) => link.textContent)).toEqual(['Search typo', 'Checkout failing'])
    expect(within(queue).getByText('Response · 40 min left')).toBeInTheDocument()
    expect(within(queue).getByText('Response · Breached · 5 min overdue')).toBeInTheDocument()

    const inProgress = screen.getByRole('region', { name: 'In progress' })
    const refunds = within(inProgress).getByRole('row', { name: /Refunds delayed/ })
    expect(refunds).toHaveTextContent('Dev Engineer')
    expect(refunds).toHaveTextContent('Response · Met')
    expect(refunds).toHaveTextContent('Resolution · 2 h 10 min left')
    expect(within(inProgress).queryByText('Slow search')).not.toBeInTheDocument()
    expect(screen.getByText('3 active · 2 waiting for acknowledgement · 1 past an SLA target')).toBeInTheDocument()
  })

  it('acknowledges with the version the list showed, and reads the list again', async () => {
    const fetchMock = renderOverview(engineer, {
      'POST /api/incidents/7/acknowledge': { body: { reference: 'INC-7' } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge and take Lead: INC-7' }))

    await waitFor(() => {
      const posts = fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/incidents/7/acknowledge' && init?.method === 'POST')
      expect(posts.map(([, init]) => JSON.parse(String(init?.body)))).toEqual([{ expectedVersion: 4 }])
    })
  })

  it('says the incident changed after a 409, and reloads the list', async () => {
    const fetchMock = renderOverview(engineer, {
      'POST /api/incidents/7/acknowledge': { status: 409, body: { status: 409, message: 'Incident INC-7 was changed by someone else' } },
    })
    await waiting()
    expect(listReads(fetchMock)).toBe(1)

    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge and take Lead: INC-7' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('INC-7 changed after the list was loaded: someone acknowledged it, or it escalated.')
    await waitFor(() => expect(listReads(fetchMock)).toBe(2))
  })

  it('shows a Viewer the same lists without the button', async () => {
    renderOverview(viewer)

    const queue = await waiting()
    expect(within(queue).getByRole('link', { name: 'Checkout failing' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Acknowledge and take Lead/ })).not.toBeInTheDocument()
  })

  it('confirms an acknowledgement, and shows why one failed', async () => {
    const success = vi.spyOn(toast, 'success')
    const failure = vi.spyOn(toast, 'error')
    renderOverview(engineer, {
      'POST /api/incidents/7/acknowledge': {
        body: {
          id: 7, reference: 'INC-7', title: 'Checkout failing', description: 'Card payments fail at the last step.', service: { id: 1, name: 'Payments' },
          severity: 'SEV2', status: 'ACKNOWLEDGED', version: 5, reporter: { id: 2, displayName: 'Dev Engineer' },
          assignee: { id: 2, displayName: 'Dev Engineer' }, roles: { comms: null, ops: null }, roleWarning: null,
          createdAt: '2026-10-20T08:30:00Z', responseTargetMinutes: 60, resolutionTargetMinutes: 480,
          responseDueAt: '2026-10-20T09:30:00Z', resolutionDueAt: '2026-10-20T16:30:00Z', timeline: [],
        },
      },
      'POST /api/incidents/8/acknowledge': { status: 403, body: { status: 403, message: 'You do not have permission to do that' } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge and take Lead: INC-7' }))
    await waitFor(() => expect(success).toHaveBeenCalledWith('INC-7 acknowledged. You are Lead, and escalation has stopped.'))

    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge and take Lead: INC-8' }))
    await waitFor(() => expect(failure).toHaveBeenCalledWith('You do not have permission to do that'))
  })

  it('says when nothing is waiting or in progress', async () => {
    renderOverview(engineer, { 'GET /api/incidents': { body: [] } })

    expect(await screen.findByText('Nothing is waiting. Every open incident has a Lead.')).toBeInTheDocument()
    expect(screen.getByText('No incident is being worked on.')).toBeInTheDocument()
  })

  it('shows an incident in progress with no Lead, and counts those past a target', async () => {
    renderOverview(engineer, {
      'GET /api/incidents': {
        body: [
          row({ id: 9, reference: 'INC-9', title: 'Queue backlog', status: 'MONITORING', createdAt: '2026-10-20T01:00:00Z', resolutionDueAt: '2026-10-20T08:00:00Z', acknowledgedAt: '2026-10-20T01:05:00Z' }),
          row({ id: 10, reference: 'INC-10', title: 'Login slow', status: 'IDENTIFIED', createdAt: '2026-10-20T02:00:00Z', resolutionDueAt: '2026-10-20T08:30:00Z', acknowledgedAt: '2026-10-20T02:05:00Z', assignee: { id: 2, displayName: 'Dev Engineer' } }),
        ],
      },
    })

    const inProgress = await screen.findByRole('region', { name: 'In progress' })
    expect(within(inProgress).getByRole('row', { name: /Queue backlog/ })).toHaveTextContent('No Lead yet')
    expect(within(inProgress).getByText('2 incidents are past an SLA target')).toBeInTheDocument()
  })

  it('shows a failed load with a retry', async () => {
    renderOverview(engineer, { 'GET /api/incidents': { status: 500, body: { status: 500, message: 'Incidents are unavailable' } } })

    expect(await screen.findByText('Incidents are unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
