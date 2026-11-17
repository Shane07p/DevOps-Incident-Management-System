import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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

// Out of order on purpose: the page must still show the newest first.
const summaries = [
  { id: 5, reference: 'INC-5', title: 'Slow search', serviceName: 'Payments', severity: 'SEV3', status: 'RESOLVED', createdAt: '2026-10-18T09:00:00Z', responseDueAt: '2026-10-18T13:00:00Z', resolutionDueAt: '2026-10-20T09:00:00Z', version: 0 },
  { id: 7, reference: 'INC-7', title: 'Checkout failing', serviceName: 'Payments', severity: 'SEV2', status: 'OPEN', createdAt: '2026-10-20T08:30:00Z', responseDueAt: '2026-10-20T09:30:00Z', resolutionDueAt: '2026-10-20T16:30:00Z', version: 0 },
  { id: 6, reference: 'INC-6', title: 'Refunds delayed', serviceName: 'Payments', severity: 'SEV1', status: 'INVESTIGATING', createdAt: '2026-10-19T12:00:00Z', responseDueAt: '2026-10-19T12:15:00Z', resolutionDueAt: '2026-10-19T16:00:00Z', version: 0 },
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
  version: 3,
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

const postsTo = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')

async function choose(label: string, option: RegExp | string) {
  await userEvent.click(screen.getByLabelText(label))
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('Incident list', () => {
  it('shows incidents newest first, with INC- references and severity and status badges', async () => {
    renderApp('ENGINEER', '/incidents')

    await screen.findByRole('link', { name: 'Checkout failing' })
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getAllByRole('cell')[0].textContent)).toEqual(['INC-7', 'INC-6', 'INC-5'])
    expect(within(rows[0]).getByText('SEV2')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Open')).toBeInTheDocument()
    expect(within(rows[0]).getByText('20 Oct, 14:00 IST')).toBeInTheDocument()
  })

  it('offers an Engineer the Declare incident button, which opens the declare dialog', async () => {
    renderApp('ENGINEER', '/incidents')

    await userEvent.click(await screen.findByRole('button', { name: 'Declare incident' }))
    expect(await screen.findByRole('dialog', { name: 'Declare an incident' })).toBeInTheDocument()
  })

  // TC-FR-01.2e: a Viewer sees no Declare incident button, and is refused at /incidents/new.
  it('shows a Viewer no Declare incident button', async () => {
    renderApp('VIEWER', '/incidents')

    await screen.findByRole('link', { name: 'Checkout failing' })
    expect(screen.queryByRole('button', { name: 'Declare incident' })).not.toBeInTheDocument()
  })

  it('sends a Viewer who opens /incidents/new back home, without the form', async () => {
    const { router } = renderApp('VIEWER', '/incidents/new')

    await waitFor(() => expect(router.state.location.pathname).toBe('/home'))
    expect(screen.queryByRole('heading', { name: 'Declare incident' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
  })
})

describe('Declare incident form', () => {
  it('uses the request field names as input ids', async () => {
    renderApp('ENGINEER', '/incidents/new')

    expect(await screen.findByLabelText('Title')).toHaveAttribute('id', 'title')
    expect(screen.getByLabelText('Description')).toHaveAttribute('id', 'description')
    expect(screen.getByLabelText('Service')).toHaveAttribute('id', 'serviceId')
    expect(screen.getByLabelText('Severity')).toHaveAttribute('id', 'severity')
  })

  // TC-FR-02.1d: an empty submit shows a field error under each input and sends nothing.
  it('shows an empty submit as field errors and sends nothing', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/new')

    await userEvent.click(await screen.findByRole('button', { name: 'Declare incident' }))

    expect(await screen.findByText('Enter a title')).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveAccessibleDescription('Enter a title')
    expect(screen.getByLabelText('Description')).toHaveAccessibleDescription('Describe what is happening')
    expect(screen.getByLabelText('Service')).toHaveAccessibleDescription('Choose a service')
    expect(screen.getByLabelText('Severity')).toHaveAccessibleDescription('Choose a severity')
    expect(postsTo(fetchMock)).toHaveLength(0)
  })

  it('declares the incident and opens it', async () => {
    const { fetchMock, router } = renderApp('ENGINEER', '/incidents/new', {
      'POST /api/incidents': { status: 201, body: checkoutFailing },
    })

    await userEvent.type(await screen.findByLabelText('Title'), 'Checkout failing')
    await userEvent.type(screen.getByLabelText('Description'), 'Card payments fail at the last step.')
    await waitFor(() => expect(screen.getByLabelText('Service')).toBeEnabled())
    await choose('Service', 'Payments')
    await choose('Severity', /SEV2/)
    await userEvent.click(screen.getByRole('button', { name: 'Declare incident' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/incidents/7'))
    const [[url, init]] = postsTo(fetchMock)
    expect(url).toBe('/api/incidents')
    expect(JSON.parse(String(init?.body))).toEqual({
      title: 'Checkout failing',
      description: 'Card payments fail at the last step.',
      serviceId: 1,
      severity: 'SEV2',
    })
    expect(await screen.findByRole('heading', { name: 'Checkout failing' })).toBeInTheDocument()
  })

  it('puts the server field errors under the matching inputs', async () => {
    renderApp('ENGINEER', '/incidents/new', {
      'POST /api/incidents': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { serviceId: 'Service not found' } },
      },
    })

    await userEvent.type(await screen.findByLabelText('Title'), 'Checkout failing')
    await userEvent.type(screen.getByLabelText('Description'), 'Card payments fail.')
    await waitFor(() => expect(screen.getByLabelText('Service')).toBeEnabled())
    await choose('Service', 'Payments')
    await choose('Severity', /SEV2/)
    await userEvent.click(screen.getByRole('button', { name: 'Declare incident' }))

    expect(await screen.findByText('Service not found')).toBeInTheDocument()
    expect(screen.getByLabelText('Service')).toHaveAccessibleDescription('Service not found')
  })
})

describe('Incident detail', () => {
  // TC-FR-21.2b: the form follows the server's rule (an Admin, or the incident's Lead or Comms); Viewers see nothing.
  it('shows a Viewer no public updates', async () => {
    renderApp('VIEWER', '/incidents/7')

    expect(await screen.findByRole('heading', { name: 'Checkout failing' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Public updates' })).not.toBeInTheDocument()
  })

  it('tells an Engineer who is not the Lead or Comms who posts, and links the status page', async () => {
    renderApp('ENGINEER', '/incidents/7')

    expect(await screen.findByRole('heading', { name: 'Public updates' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Post public update' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View the public status page' })).toHaveAttribute('href', '/status/dev-org')
  })

  it.each([
    ['Lead', { assignee: { id: 2, displayName: 'Dev Engineer' } }],
    ['Comms', { roles: { comms: { id: 2, displayName: 'Dev Engineer' }, ops: null } }],
  ])('lets the incident %s post a public update', async (_, holder) => {
    const message = 'Some card payments are failing. Next update in 30 minutes.'
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7', {
      'GET /api/incidents/7': { body: { ...checkoutFailing, ...holder } },
      'POST /api/incidents/7/public-updates': {
        status: 201,
        body: { id: 9, status: 'INVESTIGATING', message, createdAt: '2026-10-20T09:00:00Z' },
      },
    })

    await userEvent.type(await screen.findByLabelText('Public message'), message)
    await userEvent.click(screen.getByRole('button', { name: 'Post public update' }))

    await waitFor(() => expect(screen.getByLabelText('Public message')).toHaveValue(''))
    const [, init] = fetchMock.mock.calls.find(([url]) => url === '/api/incidents/7/public-updates')!
    expect(JSON.parse(init!.body as string)).toEqual({ status: 'INVESTIGATING', message })
  })

  it('shows an Admin why the server refused an update', async () => {
    renderApp('ADMIN', '/incidents/7', {
      'POST /api/incidents/7/public-updates': {
        status: 409,
        body: { status: 409, message: "This incident's service is not on the public status page; an Admin can add it", errors: {} },
      },
    })

    await userEvent.type(await screen.findByLabelText('Public message'), 'Payments are slow.')
    await userEvent.click(screen.getByRole('button', { name: 'Post public update' }))

    expect(await screen.findByText(/not on the public status page/)).toBeInTheDocument()
  })

  it('needs a message before it posts', async () => {
    const { fetchMock } = renderApp('ADMIN', '/incidents/7')

    await userEvent.click(await screen.findByRole('button', { name: 'Post public update' }))

    expect(await screen.findByText('Write what customers should know')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/incidents/7/public-updates')).toBe(false)
  })

  it('shows the incident, both SLA clocks, and its timeline', async () => {
    // 14:20 IST: 40 minutes of the 60-minute response target are left.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-20T08:50:00Z'))
    renderApp('VIEWER', '/incidents/7')

    expect(await screen.findByRole('heading', { name: 'Checkout failing' })).toBeInTheDocument()
    expect(screen.getByText('INC-7')).toBeInTheDocument()
    expect(screen.getByText('No Lead yet')).toBeInTheDocument()

    const sla = screen.getByRole('region', { name: 'SLA' })
    expect(within(sla).getByRole('group', { name: 'Response clock' })).toHaveTextContent('40 min left')
    expect(within(sla).getByRole('group', { name: 'Response clock' })).toHaveTextContent('Due 20 Oct, 15:00 IST, 1 h from declaration')
    expect(within(sla).getByRole('group', { name: 'Resolution clock' })).toHaveTextContent('7 h 40 min left')
    expect(within(sla).getByRole('group', { name: 'Resolution clock' })).toHaveTextContent('Due 20 Oct, 22:00 IST, 8 h from declaration')

    const timeline = screen.getByRole('region', { name: 'Timeline' })
    expect(within(timeline).getByText('Incident created')).toBeInTheDocument()
    expect(within(timeline).getByText('Reported as SEV2 on Payments')).toBeInTheDocument()
  })

  it('says so when the incident does not exist', async () => {
    renderApp('VIEWER', '/incidents/99', {
      'GET /api/incidents/99': { status: 404, body: { status: 404, message: 'Incident not found' } },
    })

    expect(await screen.findByText('Incident not found')).toBeInTheDocument()
  })
})

// Severity, time left, and newest each give a different order, so each sort key is really used.
// INC-8 is SEV4 but its response is 5 minutes overdue; INC-5 is resolved, so neither of its clocks runs.
const board = [
  { id: 5, reference: 'INC-5', title: 'Slow search', serviceName: 'Payments', severity: 'SEV3', status: 'RESOLVED', createdAt: '2026-10-18T09:00:00Z', responseDueAt: '2026-10-18T13:00:00Z', resolutionDueAt: '2026-10-20T09:00:00Z', version: 0 },
  { id: 7, reference: 'INC-7', title: 'Checkout failing', serviceName: 'Payments', severity: 'SEV2', status: 'OPEN', createdAt: '2026-10-20T08:30:00Z', responseDueAt: '2026-10-20T09:30:00Z', resolutionDueAt: '2026-10-20T16:30:00Z', version: 0 },
  { id: 6, reference: 'INC-6', title: 'Refunds delayed', serviceName: 'Payments', severity: 'SEV1', status: 'INVESTIGATING', createdAt: '2026-10-20T07:00:00Z', responseDueAt: '2026-10-20T07:15:00Z', resolutionDueAt: '2026-10-20T11:00:00Z', version: 0 },
  { id: 8, reference: 'INC-8', title: 'Search typo', serviceName: 'Payments', severity: 'SEV4', status: 'OPEN', createdAt: '2026-10-19T09:30:00Z', responseDueAt: '2026-10-20T08:45:00Z', resolutionDueAt: '2026-10-26T09:30:00Z', version: 0 },
]

const references = () => screen.getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[0].textContent)

describe('Incident board sorting (TC-FR-16.3f)', () => {
  // 14:20 IST on 20 Oct.
  const now = new Date('2026-10-20T08:50:00Z')

  function renderBoard(path = '/incidents') {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(now)
    return renderApp('VIEWER', path, { 'GET /api/incidents': { body: board } })
  }

  it('starts newest first', async () => {
    renderBoard()

    await screen.findByText('Search typo')
    expect(references()).toEqual(['INC-7', 'INC-6', 'INC-8', 'INC-5'])
    expect(screen.getByRole('button', { name: 'Newest' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('sorts by severity, SEV1 first, and keeps the choice in the URL', async () => {
    const { router } = renderBoard()

    await userEvent.click(await screen.findByRole('button', { name: 'Severity' }))

    expect(references()).toEqual(['INC-6', 'INC-7', 'INC-5', 'INC-8'])
    expect(router.state.location.search).toBe('?sort=severity')
  })

  it('sorts by time left on the running clock, overdue first and stopped clocks last', async () => {
    renderBoard('/incidents?sort=time-left')

    await screen.findByText('Search typo')
    expect(references()).toEqual(['INC-8', 'INC-7', 'INC-6', 'INC-5'])
  })

  it('shows the clock that still runs on each incident', async () => {
    renderBoard()

    await screen.findByText('Search typo')
    const row = (reference: string) => screen.getByRole('row', { name: new RegExp(reference) })
    expect(row('INC-8')).toHaveTextContent('Response · Breached · 5 min overdue')
    expect(row('INC-7')).toHaveTextContent('Response · 40 min left')
    expect(row('INC-6')).toHaveTextContent('Resolution · 2 h 10 min left')
    expect(row('INC-5')).toHaveTextContent('Clocks stopped')
  })
})

describe('Incident detail SLA clocks', () => {
  // 17:00 IST: past the 15:00 response due time, before the 22:00 resolution due time.
  function renderAt(incident: Record<string, unknown>) {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-20T11:30:00Z'))
    renderApp('VIEWER', '/incidents/7', { 'GET /api/incidents/7': { body: { ...checkoutFailing, ...incident } } })
  }

  it('stops the response clock at the acknowledgement and keeps the resolution clock running', async () => {
    renderAt({ status: 'INVESTIGATING', acknowledgedAt: '2026-10-20T09:10:00Z' })

    expect(await screen.findByRole('group', { name: 'Response clock' })).toHaveTextContent('Met')
    expect(screen.getByRole('group', { name: 'Response clock' })).toHaveTextContent('Stopped 20 Oct, 14:40 IST')
    expect(screen.getByRole('group', { name: 'Resolution clock' })).toHaveTextContent('5 h left')
  })

  it('marks a response clock that ran past its target, before anyone acknowledged', async () => {
    renderAt({})

    expect(await screen.findByRole('group', { name: 'Response clock' })).toHaveTextContent('Breached · 2 h overdue')
  })

  it('stops the resolution clock at RESOLVED, and judges each clock by when it stopped', async () => {
    renderAt({ status: 'RESOLVED', acknowledgedAt: '2026-10-20T09:40:00Z', resolvedAt: '2026-10-20T11:00:00Z' })

    expect(await screen.findByRole('group', { name: 'Response clock' })).toHaveTextContent('Breached')
    expect(screen.getByRole('group', { name: 'Response clock' })).toHaveTextContent('10 min late')
    expect(screen.getByRole('group', { name: 'Resolution clock' })).toHaveTextContent('Met')
  })
})

describe('Incident list views and filters (#266)', () => {
  // A reload: the page is built again from nothing but its address.
  const cleanupAndRender = (path: string) => {
    cleanup()
    renderApp('VIEWER', path, { 'GET /api/incidents': { body: board } })
  }
  const titles = () => screen.getAllByRole('link').map((link) => link.textContent).filter((text) => ['Slow search', 'Checkout failing', 'Refunds delayed', 'Search typo'].includes(text ?? ''))

  it('keeps search and filters in the URL when switching to the board, and after a reload', async () => {
    const { router } = renderApp('VIEWER', '/incidents', { 'GET /api/incidents': { body: board } })
    await screen.findByText('Search typo')

    await userEvent.type(screen.getByLabelText('Search incidents'), 'search')
    expect(titles()).toEqual(['Search typo', 'Slow search'])
    await choose('Filter severity', 'SEV4')
    expect(titles()).toEqual(['Search typo'])

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))

    const query = new URLSearchParams(router.state.location.search)
    expect(Object.fromEntries(query)).toEqual({ q: 'search', severity: 'SEV4', view: 'board' })
    expect(screen.getByRole('region', { name: /Needs response/ })).toHaveTextContent('Search typo')
    expect(screen.queryByText('Slow search')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Search incidents')).toHaveValue('search')

    // A reload opens the same address: the board, with the same filters.
    cleanupAndRender(`/incidents${router.state.location.search}`)
    expect(await screen.findByRole('region', { name: /Needs response/ })).toHaveTextContent('Search typo')
    expect(screen.queryByText('Slow search')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Board' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows only finished incidents under History, and only the caller\'s under Assigned to me', async () => {
    const mine = board.map((incident) => (incident.id === 6 ? { ...incident, assignee: { id: 2, displayName: 'Dev Engineer' } } : incident))
    renderApp('ENGINEER', '/incidents?scope=history', { 'GET /api/incidents': { body: mine } })

    await screen.findByText('Slow search')
    expect(titles()).toEqual(['Slow search'])

    await userEvent.click(screen.getByRole('button', { name: /Assigned to me/ }))
    expect(titles()).toEqual(['Refunds delayed'])
  })

  it('previews an incident in a drawer without leaving the list', async () => {
    const { router } = renderApp('VIEWER', '/incidents', { 'GET /api/incidents': { body: board } })

    await userEvent.click(await screen.findByRole('button', { name: 'Preview INC-7' }))

    const drawer = await screen.findByRole('dialog', { name: 'Checkout failing' })
    expect(within(drawer).getByText('Card payments fail at the last step.')).toBeInTheDocument()
    expect(within(drawer).getByRole('link', { name: 'Open incident' })).toHaveAttribute('href', '/incidents/7')
    expect(router.state.location.pathname).toBe('/incidents')
  })

  it('shows the chosen service\'s targets for the chosen severity in the declare dialog', async () => {
    renderApp('ENGINEER', '/incidents')

    await userEvent.click(await screen.findByRole('button', { name: 'Declare incident' }))
    const dialog = await screen.findByRole('dialog', { name: 'Declare an incident' })
    await waitFor(() => expect(within(dialog).getByLabelText('Service')).toBeEnabled())
    await choose('Severity', /SEV1/)
    expect(within(dialog).getByText('Choose a service to see its response and resolution targets.')).toBeInTheDocument()

    await choose('Service', 'Payments')

    const next = within(dialog).getByRole('region', { name: 'What happens next' })
    expect(next).toHaveTextContent('Critical service or business impact')
    expect(within(next).getByText('15 min')).toBeInTheDocument()
    expect(within(next).getByText('4 h')).toBeInTheDocument()
  })
})
