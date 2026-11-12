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

// A comment, and a correction of it: the correction links to the comment, and the comment to its correction.
const withComments = {
  ...checkoutFailing,
  timeline: [
    ...checkoutFailing.timeline,
    { id: 4, eventType: 'COMMENT', actor: 'Dev Engineer', details: 'Gateway returns 502', recordedAt: '2026-10-20T08:40:00Z', correctsEntryId: null },
    { id: 5, eventType: 'CORRECTION', actor: 'Dev Admin', details: 'It was 504, not 502', recordedAt: '2026-10-20T08:45:00Z', correctsEntryId: 4 },
  ],
}

const commentEntry = (comment: string) => ({
  status: 201,
  body: { id: 9, eventType: 'COMMENT', actor: 'Dev Engineer', details: comment, recordedAt: '2026-10-20T09:00:00Z', correctsEntryId: null },
})

const posts = (fetchMock: ReturnType<typeof mockFetch>, url: string) =>
  fetchMock.mock.calls.filter(([called, init]) => called === url && init?.method === 'POST').map(([, init]) => JSON.parse(String(init?.body)))

describe('Incident timeline', () => {
  it('links a correction to what it corrects, and the original to its correction', async () => {
    renderApp('VIEWER', '/incidents/7', { 'GET /api/incidents/7': { body: withComments } })

    const comment = await screen.findByRole('listitem', { name: 'Comment by Dev Engineer, 20 Oct, 14:10 IST' })
    const correction = screen.getByRole('listitem', { name: 'Correction by Dev Admin, 20 Oct, 14:15 IST' })

    expect(comment).toHaveTextContent('Gateway returns 502')
    expect(within(comment).getByRole('link', { name: 'Correction by Dev Admin, 20 Oct, 14:15 IST' })).toHaveAttribute('href', '#entry-5')
    expect(correction).toHaveTextContent('It was 504, not 502')
    expect(within(correction).getByRole('link', { name: 'Comment by Dev Engineer, 20 Oct, 14:10 IST' })).toHaveAttribute('href', '#entry-4')
  })

  it('gives a Viewer no comment box and nothing to correct', async () => {
    renderApp('VIEWER', '/incidents/7', { 'GET /api/incidents/7': { body: withComments } })

    await screen.findByText('Gateway returns 502')
    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Correct / })).not.toBeInTheDocument()
  })

  it('posts a comment and clears the box', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7', { 'POST /api/incidents/7/comments': commentEntry('Rolled back the gateway') })

    await userEvent.type(await screen.findByLabelText('Add a comment'), 'Rolled back the gateway')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    await waitFor(() => expect(posts(fetchMock, '/api/incidents/7/comments')).toEqual([{ comment: 'Rolled back the gateway' }]))
    expect(screen.getByLabelText('Add a comment')).toHaveValue('')
  })

  // TC-FR-09.2a: comments stay allowed on a CLOSED incident.
  it('still takes comments once the incident is CLOSED', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7', {
      'GET /api/incidents/7': { body: { ...checkoutFailing, status: 'CLOSED' } },
      'POST /api/incidents/7/comments': commentEntry('Follow-up: add a gateway alert'),
    })

    await userEvent.type(await screen.findByLabelText('Add a comment'), 'Follow-up: add a gateway alert')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    await waitFor(() => expect(posts(fetchMock, '/api/incidents/7/comments')).toHaveLength(1))
  })

  it('refuses an empty comment and one over 5,000 characters, and sends nothing', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7')

    await userEvent.click(await screen.findByRole('button', { name: 'Post comment' }))
    expect(await screen.findByText('Write a comment')).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('Add a comment'))
    await userEvent.paste('x'.repeat(5001))
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))
    expect(await screen.findByText('At most 5,000 characters')).toBeInTheDocument()

    expect(posts(fetchMock, '/api/incidents/7/comments')).toHaveLength(0)
  })

  it('corrects an entry by adding a new one linked to it', async () => {
    const { fetchMock } = renderApp('ENGINEER', '/incidents/7', {
      'GET /api/incidents/7': { body: withComments },
      'POST /api/incidents/7/timeline/4/corrections': {
        status: 201,
        body: { id: 10, eventType: 'CORRECTION', actor: 'Dev Engineer', details: 'Only card payments', recordedAt: '2026-10-20T09:05:00Z', correctsEntryId: 4 },
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Correct Comment by Dev Engineer, 20 Oct, 14:10 IST' }))
    await userEvent.type(screen.getByLabelText('Correction'), 'Only card payments')
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }))

    await waitFor(() => expect(posts(fetchMock, '/api/incidents/7/timeline/4/corrections')).toEqual([{ details: 'Only card payments' }]))
    await waitFor(() => expect(screen.queryByLabelText('Correction')).not.toBeInTheDocument())
  })

  it("shows the server's message under the comment box", async () => {
    renderApp('ENGINEER', '/incidents/7', {
      'POST /api/incidents/7/comments': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { comment: 'Comment must not exceed 5000 characters' } },
      },
    })

    await userEvent.type(await screen.findByLabelText('Add a comment'), 'Looks fine')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    expect(await screen.findByText('Comment must not exceed 5000 characters')).toBeInTheDocument()
  })
})
