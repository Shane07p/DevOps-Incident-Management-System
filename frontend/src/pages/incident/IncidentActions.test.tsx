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
import { devOrg, mockFetch, users } from '@/test/fetchMock'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' },
}

const incident = {
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
  roles: { comms: null, ops: null },
  roleWarning: null,
  createdAt: '2026-10-20T08:30:00Z',
  responseTargetMinutes: 60,
  resolutionTargetMinutes: 480,
  responseDueAt: '2026-10-20T09:30:00Z',
  resolutionDueAt: '2026-10-20T16:30:00Z',
  timeline: [],
}

type Routes = Parameters<typeof mockFetch>[0]

// What differs from the incident above, such as its status, assignee, or roles.
function renderIncident(role: Role, shown: Record<string, unknown> = {}, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: signedIn[role], org: devOrg } },
    'GET /api/incidents/7': { body: { ...incident, ...shown } },
    'GET /api/services/1/runbooks': { body: [] },
    'GET /api/users': { body: users },
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

const sent = (fetchMock: ReturnType<typeof mockFetch>, method: string, url: string) =>
  fetchMock.mock.calls.filter(([called, init]) => called === url && init?.method === method).map(([, init]) => JSON.parse(String(init?.body)))

const actions = () => screen.getByRole('region', { name: 'Actions and roles' })

async function choose(label: string, option: string) {
  await userEvent.click(screen.getByRole('combobox', { name: label }))
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Incident actions by role', () => {
  it('shows a Viewer who holds each role, and no actions', async () => {
    renderIncident('VIEWER', { status: 'INVESTIGATING', assignee: { id: 2, displayName: 'Dev Engineer' }, roles: { comms: { id: 1, displayName: 'Dev Admin' }, ops: null } })

    await screen.findByRole('heading', { name: 'Checkout failing' })
    expect(actions()).toHaveTextContent('LeadDev Engineer')
    expect(actions()).toHaveTextContent('CommsDev Admin')
    expect(actions()).toHaveTextContent('OpsNobody yet')
    expect(within(actions()).queryAllByRole('button')).toHaveLength(0)
  })

  it('offers an Engineer Acknowledge on an OPEN incident, and no status change', async () => {
    renderIncident('ENGINEER')

    expect(await screen.findByRole('button', { name: 'Acknowledge' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change status' })).not.toBeInTheDocument()
  })

  it('offers no actions once CLOSED', async () => {
    renderIncident('ENGINEER', { status: 'CLOSED' })

    await screen.findByRole('heading', { name: 'Checkout failing' })
    expect(within(actions()).queryAllByRole('button')).toHaveLength(0)
  })
})

describe('Acknowledge', () => {
  it('sends the version on screen and shows the incident the server returns', async () => {
    const fetchMock = renderIncident('ENGINEER', {}, {
      'POST /api/incidents/7/acknowledge': {
        body: { ...incident, status: 'ACKNOWLEDGED', version: 4, assignee: { id: 2, displayName: 'Dev Engineer' } },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }))

    expect(await screen.findByRole('button', { name: 'Change status' })).toBeInTheDocument()
    expect(sent(fetchMock, 'POST', '/api/incidents/7/acknowledge')).toEqual([{ expectedVersion: 3 }])
    expect(actions()).toHaveTextContent('LeadDev Engineer')
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
  })

  // TC-US-08c from the screen: the other person's acknowledgement won the race.
  it('says the incident changed after a 409, and shows the latest version', async () => {
    let reads = 0
    renderIncident('ENGINEER', {}, {
      'GET /api/incidents/7': () => {
        reads += 1
        return reads === 1
          ? { body: incident }
          : { body: { ...incident, status: 'ACKNOWLEDGED', version: 4, assignee: { id: 1, displayName: 'Dev Admin' } } }
      },
      'POST /api/incidents/7/acknowledge': {
        status: 409,
        body: { status: 409, message: 'This record was changed by another request; reload and try again' },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('INC-7 changed while you were looking at it, so nothing was saved.')
    await waitFor(() => expect(actions()).toHaveTextContent('LeadDev Admin'))
    expect(reads).toBe(2)
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
  })
})

describe('Change status', () => {
  it.each([
    ['ACKNOWLEDGED', ['INVESTIGATING']],
    ['INVESTIGATING', ['IDENTIFIED', 'MONITORING']],
    ['IDENTIFIED', ['MONITORING']],
    ['MONITORING', ['RESOLVED', 'INVESTIGATING']],
    ['RESOLVED', ['CLOSED']],
  ])('from %s offers only %j', async (status, offered) => {
    renderIncident('ENGINEER', { status })

    await userEvent.click(await screen.findByRole('button', { name: 'Change status' }))
    await userEvent.click(screen.getByRole('combobox', { name: 'New status' }))

    const options = await screen.findAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual(offered)
  })

  it('needs a recovery note to resolve, then sends it with the version', async () => {
    const fetchMock = renderIncident('ENGINEER', { status: 'MONITORING' }, {
      'POST /api/incidents/7/transitions': { body: { ...incident, status: 'RESOLVED', version: 4 } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Change status' }))
    await choose('New status', 'RESOLVED')
    await userEvent.click(screen.getByRole('button', { name: 'Change status' }))

    expect(await screen.findByText('Say how the service recovered')).toBeInTheDocument()
    expect(sent(fetchMock, 'POST', '/api/incidents/7/transitions')).toHaveLength(0)

    await userEvent.type(screen.getByLabelText('Recovery note'), 'Rolled back release 4.2')
    await userEvent.click(screen.getByRole('button', { name: 'Change status' }))

    await waitFor(() =>
      expect(sent(fetchMock, 'POST', '/api/incidents/7/transitions')).toEqual([
        { status: 'RESOLVED', note: 'Rolled back release 4.2', expectedVersion: 3 },
      ]),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('closes the dialog and reports a 409 on the page', async () => {
    renderIncident('ENGINEER', { status: 'INVESTIGATING' }, {
      'POST /api/incidents/7/transitions': {
        status: 409,
        body: { status: 409, message: 'This record was changed by another request; reload and try again' },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Change status' }))
    await choose('New status', 'IDENTIFIED')
    await userEvent.click(screen.getByRole('button', { name: 'Change status' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('INC-7 changed while you were looking at it')
  })
})

describe('Change severity', () => {
  it('needs a reason, and does not offer the current severity', async () => {
    const fetchMock = renderIncident('ENGINEER', { status: 'INVESTIGATING' }, {
      'PUT /api/incidents/7/severity': { body: { ...incident, status: 'INVESTIGATING', severity: 'SEV1', version: 4 } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Change severity' }))
    await userEvent.click(screen.getByRole('combobox', { name: 'New severity' }))
    expect((await screen.findAllByRole('option')).map((option) => option.textContent)).toEqual(['SEV1', 'SEV3', 'SEV4'])
    await userEvent.click(screen.getByRole('option', { name: 'SEV1' }))
    await userEvent.click(screen.getByRole('button', { name: 'Change severity' }))

    expect(await screen.findByText('Give a reason for the change')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Reason'), 'Every region is down')
    await userEvent.click(screen.getByRole('button', { name: 'Change severity' }))

    await waitFor(() =>
      expect(sent(fetchMock, 'PUT', '/api/incidents/7/severity')).toEqual([{ severity: 'SEV1', reason: 'Every region is down', expectedVersion: 3 }]),
    )
  })
})

describe('Reassign and roles', () => {
  it('lets an Admin choose any member as the new Lead', async () => {
    const fetchMock = renderIncident('ADMIN', { status: 'INVESTIGATING', assignee: { id: 1, displayName: 'Dev Admin' } }, {
      'PUT /api/incidents/7/assignee': { body: { ...incident, status: 'INVESTIGATING', version: 4, assignee: { id: 2, displayName: 'Dev Engineer' } } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Reassign' }))
    await choose('New Lead', 'Dev Engineer')
    await userEvent.click(screen.getByRole('button', { name: 'Reassign' }))

    await waitFor(() => expect(sent(fetchMock, 'PUT', '/api/incidents/7/assignee')).toEqual([{ assigneeId: 2, expectedVersion: 3 }]))
    await waitFor(() => expect(actions()).toHaveTextContent('LeadDev Engineer'))
  })

  // Only Admins can list members, so an Engineer can take the incident or a role for themselves.
  it('offers an Engineer only themselves', async () => {
    renderIncident('ENGINEER', { status: 'INVESTIGATING' })

    await userEvent.click(await screen.findByRole('button', { name: 'Give a role' }))
    await userEvent.click(screen.getByRole('combobox', { name: 'Person' }))

    expect((await screen.findAllByRole('option')).map((option) => option.textContent)).toEqual(['Dev Engineer'])
  })

  it('gives the Ops role and shows the warning for one person holding two roles', async () => {
    const fetchMock = renderIncident('ADMIN', { status: 'INVESTIGATING', severity: 'SEV1', assignee: { id: 2, displayName: 'Dev Engineer' } }, {
      'PUT /api/incidents/7/roles': {
        body: {
          ...incident,
          status: 'INVESTIGATING',
          severity: 'SEV1',
          version: 4,
          assignee: { id: 2, displayName: 'Dev Engineer' },
          roles: { comms: null, ops: { id: 2, displayName: 'Dev Engineer' } },
          roleWarning: 'Dev Engineer holds Lead and Ops on a SEV1 incident',
        },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Give a role' }))
    await choose('Role', 'Ops: investigates and fixes')
    await choose('Person', 'Dev Engineer')
    await userEvent.click(screen.getByRole('button', { name: 'Give role' }))

    await waitFor(() => expect(sent(fetchMock, 'PUT', '/api/incidents/7/roles')).toEqual([{ role: 'OPS', userId: 2, expectedVersion: 3 }]))
    expect(await screen.findByText('Dev Engineer holds Lead and Ops on a SEV1 incident')).toBeInTheDocument()
  })

  it("shows the server's field error in the dialog", async () => {
    renderIncident('ADMIN', { status: 'INVESTIGATING', assignee: { id: 1, displayName: 'Dev Admin' } }, {
      'PUT /api/incidents/7/assignee': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { assigneeId: 'Assignee must be an enabled user' } },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Reassign' }))
    await choose('New Lead', 'Dev Engineer')
    await userEvent.click(screen.getByRole('button', { name: 'Reassign' }))

    expect(await screen.findByText('Assignee must be an enabled user')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
