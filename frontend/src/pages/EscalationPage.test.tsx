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

// Out of order on purpose: the page pages level 1 first whatever order the server lists them in.
const policy = {
  id: 7,
  serviceId: 1,
  steps: [
    { id: 22, stepNumber: 2, responderId: 1, responderDisplayName: 'Dev Admin', waitMinutes: 30 },
    { id: 21, stepNumber: 1, responderId: 2, responderDisplayName: 'Dev Engineer', waitMinutes: 15 },
  ],
  updatedAt: '2026-10-29T10:00:00Z',
}
const schedule = { serviceId: 1, fallbackContactId: 1, fallbackContactName: 'admin', shifts: [] }
const saved = { ...policy, updatedAt: '2026-10-30T10:00:00Z' }

type Routes = Parameters<typeof mockFetch>[0]

function renderEscalation(role: Role, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: signedIn[role], org: devOrg } },
    'GET /api/services': { body: [payments] },
    'GET /api/services/1/escalation-policy': { body: policy },
    'GET /api/services/1/on-call': { body: schedule },
    'GET /api/users': { body: users },
    'PUT /api/services/1/escalation-policy': { body: saved },
    ...extraRoutes,
  })
  const router = createMemoryRouter(routes, { initialEntries: ['/escalation'] })
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

const puts = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')

it('renders escalation with a missing service version', async () => {
  renderEscalation('VIEWER', { 'GET /api/services': { body: [{ ...payments, version: undefined }] } })
  expect(await screen.findByRole('list', { name: 'Paging order' })).toBeInTheDocument()
})
const sentSteps = (fetchMock: ReturnType<typeof mockFetch>) => JSON.parse(String(puts(fetchMock)[0][1]?.body)).steps

const level = (n: number) => screen.getByRole('group', { name: `Level ${n}` })

async function chooseContact(n: number, name: string) {
  await userEvent.click(within(level(n)).getByLabelText('Contact'))
  await userEvent.click(await screen.findByRole('option', { name }))
}

async function setWait(n: number, minutes: string) {
  const input = within(level(n)).getByLabelText('Wait (minutes)')
  await userEvent.clear(input)
  if (minutes) await userEvent.type(input, minutes)
}

afterEach(() => {
  clearToken()
})

describe('EscalationPage', () => {
  it('shows every role who is paged and when, and only Admins get the form', async () => {
    renderEscalation('VIEWER')

    const order = await screen.findByRole('list', { name: 'Paging order' })
    const rows = within(order).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Dev Engineer')
    expect(rows[0]).toHaveTextContent('15 min after the incident opens · 15 min in')
    expect(rows[1]).toHaveTextContent('Dev Admin')
    expect(rows[1]).toHaveTextContent('30 min after level 1 · 45 min in')
    expect(rows[2]).toHaveTextContent('admin')
    expect(rows[2]).toHaveTextContent('Fallback contact, once, 30 min after level 2 · 1 h 15 min in')
    expect(screen.queryByRole('form', { name: 'Escalation levels' })).not.toBeInTheDocument()
  })

  it('lets an Admin give a service without a policy its first level', async () => {
    const fetchMock = renderEscalation('ADMIN', {
      'GET /api/services/1/escalation-policy': { status: 404, body: { status: 404, message: 'Escalation policy not found for service 1' } },
    })

    expect(await screen.findByText('No escalation levels yet, so incidents on this service are not escalated.')).toBeInTheDocument()
    await chooseContact(1, 'Dev Engineer (engineer@settle.local)')
    await setWait(1, '10')
    await userEvent.click(screen.getByRole('button', { name: 'Save levels' }))

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(sentSteps(fetchMock)).toEqual([{ stepNumber: 1, responderId: 2, waitMinutes: 10 }])
  })

  it('saves added, reordered, and removed levels numbered in their new order', async () => {
    const fetchMock = renderEscalation('ADMIN')
    await screen.findByRole('form', { name: 'Escalation levels' })

    // Engineer then Admin; move the Admin up and drop the Engineer.
    await userEvent.click(screen.getByRole('button', { name: 'Move level 2 up' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove level 2' }))
    expect(screen.getByRole('button', { name: 'Remove level 1' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Add level' }))
    await chooseContact(2, 'Dev Engineer (engineer@settle.local)')
    await setWait(2, '5')
    await userEvent.click(screen.getByRole('button', { name: 'Save levels' }))

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(sentSteps(fetchMock)).toEqual([
      { stepNumber: 1, responderId: 1, waitMinutes: 30 },
      { stepNumber: 2, responderId: 2, waitMinutes: 5 },
    ])
  })

  it('sends nothing while a level has no contact or a wait under one minute', async () => {
    const fetchMock = renderEscalation('ADMIN')
    await screen.findByRole('form', { name: 'Escalation levels' })

    await setWait(1, '0')
    await userEvent.click(screen.getByRole('button', { name: 'Add level' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save levels' }))

    expect(await within(level(1)).findByText('Use whole minutes, at least 1')).toBeInTheDocument()
    // The placeholder says the same, so look for the message under the field.
    expect(within(level(3)).getByText('Choose a contact', { selector: '[data-slot="form-message"]' })).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('shows why the server refused the levels', async () => {
    renderEscalation('ADMIN', {
      'PUT /api/services/1/escalation-policy': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { steps: 'Responder user engineer is disabled' } },
      },
    })
    await screen.findByRole('form', { name: 'Escalation levels' })

    await userEvent.click(screen.getByRole('button', { name: 'Save levels' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Responder user engineer is disabled')
  })

  it('says who escalation last paged on each unacknowledged incident, from its timeline', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-30T10:20:00Z'))
    const summary = (overrides: Record<string, unknown>) => ({
      serviceName: 'Payments',
      severity: 'SEV2',
      status: 'OPEN',
      createdAt: '2026-10-30T10:00:00Z',
      responseDueAt: '2026-10-30T11:00:00Z',
      resolutionDueAt: '2026-10-30T14:00:00Z',
      version: 0,
      assignee: null,
      acknowledgedAt: null,
      resolvedAt: null,
      ...overrides,
    })
    // The policy now says Dev Engineer then Dev Admin; the incident kept the steps it opened with and its
    // timeline records the page that really went out. The banner follows the timeline.
    const detail = {
      id: 9,
      reference: 'INC-9',
      title: 'Refunds slow',
      description: 'Refunds slow.',
      service: { id: 1, name: 'Payments' },
      severity: 'SEV2',
      status: 'OPEN',
      version: 1,
      reporter: null,
      assignee: null,
      createdAt: '2026-10-30T10:00:00Z',
      responseTargetMinutes: 60,
      resolutionTargetMinutes: 240,
      responseDueAt: '2026-10-30T11:00:00Z',
      resolutionDueAt: '2026-10-30T14:00:00Z',
      timeline: [
        { id: 1, eventType: 'CREATED', actor: null, details: 'Incident declared', recordedAt: '2026-10-30T10:00:00Z', correctsEntryId: null },
        { id: 2, eventType: 'ESCALATED', actor: null, details: 'Escalated to level 1: Asha Patel', recordedAt: '2026-10-30T10:12:00Z', correctsEntryId: null },
      ],
    }
    renderEscalation('VIEWER', {
      'GET /api/incidents': {
        body: [
          summary({ id: 9, reference: 'INC-9', title: 'Refunds slow' }),
          summary({ id: 10, reference: 'INC-10', title: 'Acknowledged', acknowledgedAt: '2026-10-30T10:05:00Z' }),
        ],
      },
      'GET /api/incidents/9': { body: detail },
    })

    expect(await screen.findByText(/Escalated to level 1: Asha Patel, 8 min ago\./)).toBeInTheDocument()
    expect(screen.getByText('INC-9 is not acknowledged')).toBeInTheDocument()
    expect(screen.queryByText(/Dev Engineer \(level 1\) was paged/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open INC-9' })).toHaveAttribute('href', '/incidents/9')
    expect(screen.queryByText(/INC-10 is not acknowledged/)).not.toBeInTheDocument()
    vi.useRealTimers()
  })
})
