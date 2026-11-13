import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

// Declared at 14:00 IST.
const incident = {
  id: 7,
  reference: 'INC-7',
  title: 'Checkout failing',
  description: 'Card payments fail at the last step.',
  service: { id: 1, name: 'Payments' },
  severity: 'SEV2',
  status: 'INVESTIGATING',
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
  deploys: [],
}

const deploy = { id: 41, version: 'v2.4.1', commit: 'a1b2c3d4e5f6', author: 'ci-bot', deployedAt: '2026-10-20T07:55:00Z' }

const series = (metric: string, values: number[]) => ({
  metric,
  serviceId: 1,
  serviceName: 'Payments',
  from: '2026-10-20T07:30:00Z',
  to: '2026-10-21T07:30:00Z',
  stepSeconds: 60,
  points: values.map((value, index) => ({ timestamp: new Date(Date.parse('2026-10-20T08:00:00Z') + index * 60_000).toISOString(), value })),
})

const quietTelemetry = {
  'GET /api/telemetry/logs': { body: [] },
  'GET /api/telemetry/metrics': { body: series('requestRate', []) },
}

type Routes = Parameters<typeof mockFetch>[0]

function renderIncident(role: Role, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: signedIn[role], org: devOrg } },
    'GET /api/incidents/7': { body: incident },
    'GET /api/services': { body: [payments] },
    'GET /api/services/1/runbooks': { body: [] },
    'GET /api/incidents/7/similar': { body: [] },
    ...quietTelemetry,
    ...extraRoutes,
  })
  const router = createMemoryRouter(routes, { initialEntries: ['/incidents/7'] })
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
  return fetchMock
}

const section = async (name: string) => (await screen.findByRole('heading', { name })).closest('section')!

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Incident evidence (#169)', () => {
  // TC-FR-27.2a
  it('shows each section empty, and a Viewer gets no Link or add controls', async () => {
    renderIncident('VIEWER')

    expect(within(await section('Recent deploys')).getByText('No deploys to Payments in the 2 hours before this incident.')).toBeInTheDocument()
    expect(within(await section('Evidence')).getByText('No evidence links yet.')).toBeInTheDocument()
    expect(await within(await section('Logs')).findByText('No log lines in this window.')).toBeInTheDocument()
    expect((await within(await section('Metrics')).findAllByText('No traffic in this window.'))).toHaveLength(4)
    expect(screen.queryByRole('button', { name: /^Link / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add evidence' })).not.toBeInTheDocument()
  })

  it('gives a Viewer no Link button on a deploy either', async () => {
    renderIncident('VIEWER', { 'GET /api/incidents/7': { body: { ...incident, deploys: [deploy] } } })

    const deploys = await section('Recent deploys')
    expect(within(deploys).getByText('v2.4.1')).toBeInTheDocument()
    expect(within(deploys).getByText(/35 min before/)).toBeInTheDocument()
    expect(within(deploys).queryByRole('button')).not.toBeInTheDocument()
  })

  it('lets an Engineer link a deploy, which then shows as linked', async () => {
    let linked = false
    const linkedEntry = {
      id: 9,
      eventType: 'DEPLOY_LINKED',
      actor: 'Dev Engineer',
      details: 'Linked deployment v2.4.1 (a1b2c3d4e5f6) for Payments',
      recordedAt: '2026-10-20T09:00:00Z',
      correctsEntryId: null,
    }
    const fetchMock = renderIncident('ENGINEER', {
      'GET /api/incidents/7': () => ({
        body: { ...incident, deploys: [deploy], timeline: linked ? [...incident.timeline, linkedEntry] : incident.timeline },
      }),
      'POST /api/incidents/7/deploys/41': () => {
        linked = true
        return { body: linkedEntry }
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Link v2.4.1' }))

    expect(await within(await section('Recent deploys')).findByText('Linked')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Link v2.4.1' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/incidents/7/deploys/41' && init?.method === 'POST')).toBe(true)
  })

  it('lists evidence as links that open in a new tab', async () => {
    renderIncident('VIEWER', {
      'GET /api/incidents/7': {
        body: {
          ...incident,
          timeline: [
            ...incident.timeline,
            { id: 5, eventType: 'EVIDENCE_ADDED', actor: 'Dev Engineer', details: 'Error dashboard: https://grafana.example.com/d/abc', recordedAt: '2026-10-20T09:10:00Z', correctsEntryId: null },
          ],
        },
      },
    })

    const link = within(await section('Evidence')).getByRole('link', { name: 'Error dashboard' })
    expect(link).toHaveAttribute('href', 'https://grafana.example.com/d/abc')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  // TC-FR-09.3d
  it('adds evidence, and refuses a link that is not http or https before sending it', async () => {
    const fetchMock = renderIncident('ENGINEER', {
      'POST /api/incidents/7/evidence': {
        status: 201,
        body: { id: 6, eventType: 'EVIDENCE_ADDED', actor: 'Dev Engineer', details: 'Trace: https://traces.example.com/t/1', recordedAt: '2026-10-20T09:20:00Z', correctsEntryId: null },
      },
    })
    const evidence = await section('Evidence')

    await userEvent.type(within(evidence).getByLabelText('Label'), 'Trace')
    await userEvent.type(within(evidence).getByLabelText('Link'), 'javascript:alert(1)')
    await userEvent.click(within(evidence).getByRole('button', { name: 'Add evidence' }))
    expect(await within(evidence).findByText('Use an http or https link')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/incidents/7/evidence')).toBe(false)

    await userEvent.clear(within(evidence).getByLabelText('Link'))
    await userEvent.type(within(evidence).getByLabelText('Link'), 'https://traces.example.com/t/1')
    await userEvent.click(within(evidence).getByRole('button', { name: 'Add evidence' }))

    await waitFor(() => expect(within(evidence).getByLabelText('Label')).toHaveValue(''))
    const [, init] = fetchMock.mock.calls.find(([url]) => url === '/api/incidents/7/evidence')!
    expect(JSON.parse(init!.body as string)).toEqual({ label: 'Trace', url: 'https://traces.example.com/t/1' })
  })
})

describe('Incident telemetry (#169)', () => {
  it('reads logs and metrics for the hour before the incident onward, at most 24 hours', async () => {
    const fetchMock = renderIncident('VIEWER')

    await within(await section('Logs')).findByText('No log lines in this window.')
    const telemetry = fetchMock.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.startsWith('/api/telemetry/'))
      .map((url) => new URL(url, 'http://localhost'))
    expect(telemetry.map((url) => url.searchParams.get('metric')).filter(Boolean).sort()).toEqual(['errorRate', 'latencyP50', 'latencyP95', 'requestRate'])
    for (const url of telemetry) {
      expect(url.searchParams.get('serviceId')).toBe('1')
      expect(url.searchParams.get('from')).toBe('2026-10-20T07:30:00.000Z')
      expect(Date.parse(url.searchParams.get('to')!) - Date.parse(url.searchParams.get('from')!)).toBeLessThanOrEqual(24 * 3_600_000)
    }
  })

  it('draws each metric and shows its log lines', async () => {
    renderIncident('VIEWER', {
      'GET /api/telemetry/metrics': { body: series('errorRate', [0.01, 0.2, 0.05]) },
      'GET /api/telemetry/logs': {
        body: [
          { id: '1:0', timestamp: '2026-10-20T08:29:00Z', serviceId: 1, serviceName: 'Payments', level: 'ERROR', message: 'gateway timeout', attributes: {} },
        ],
      },
    })

    expect((await screen.findAllByRole('img', { name: /Error rate: 3 points, peak 20\.0%, latest 5\.00%/ })).length).toBeGreaterThan(0)
    expect(within(await section('Logs')).getByText('gateway timeout')).toBeInTheDocument()
    expect(within(await section('Logs')).getByRole('link', { name: 'Open in Logs' })).toHaveAttribute('href', expect.stringContaining('/logs?serviceId=1'))
  })

  // TC-FR-27.2b
  it("says so once when Grafana is not set up, and the rest of the page works", async () => {
    const down = { status: 503, body: { status: 503, message: 'Metrics are not set up on this server', errors: {} } }
    renderIncident('ENGINEER', {
      'GET /api/telemetry/metrics': down,
      'GET /api/telemetry/logs': { status: 503, body: { status: 503, message: 'Logs are not set up on this server', errors: {} } },
    })

    expect(await within(await section('Metrics')).findByText('Metrics are not set up on this server. The rest of the incident works as usual.')).toBeInTheDocument()
    expect(await within(await section('Logs')).findByText('Logs are not set up on this server. The rest of the incident works as usual.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add evidence' })).toBeEnabled()
  })
})
