
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
  VIEWER: {
    id: 3,
    email: 'viewer@settle.local',
    displayName: 'Dev Viewer',
    role: 'VIEWER',
  },
}

const incident = {
  id: 7,
  reference: 'INC-7',
  title: 'Checkout failing',
  description: 'Card payments fail at the last step.',
  service: { id: 1, name: 'Payments' },
  severity: 'SEV2',
  status: 'OPEN',
  version: 1,
  reporter: { id: 2, displayName: 'Dev Engineer' },
  assignee: null,
  createdAt: '2026-10-20T08:30:00Z',
  responseTargetMinutes: 60,
  resolutionTargetMinutes: 480,
  responseDueAt: '2026-10-20T09:30:00Z',
  resolutionDueAt: '2026-10-20T16:30:00Z',
  timeline: [],
}

const draft = {
  id: 1,
  incidentId: 7,
  status: 'DRAFT',
  summary: 'Checkout outage',
  impact: 'Payments failed',
  rootCause: 'Gateway issue',
  resolution: 'Restarted gateway',
  wentWell: 'Quick detection',
  wentBadly: 'Slow escalation',
  author: { id: 2, displayName: 'Dev Engineer' },
  reviewedBy: null,
  reviewedAt: null,
  updatedAt: '2026-11-13T08:00:00Z',
  version: 1,
}

const followUp = {
  id: 11,
  incidentId: 7,
  incidentReference: 'INC-7',
  title: 'Update gateway monitoring',
  kind: 'PREVENTIVE',
  owner: { id: 2, displayName: 'Dev Engineer' },
  dueDate: '2099-01-01',
  status: 'OPEN',
  overdue: false,
  completedAt: null,
  createdAt: '2026-10-20T08:30:00Z',
}

function renderPostmortem(
  role: Role,
  extraRoutes: Record<
    string,
    {
      status?: number
      body: unknown
    } | ((init: RequestInit | undefined) => {
      status?: number
      body: unknown
    })
  > = {},
) {
  writeToken('test-token')

  const fetchMock = mockFetch({
    'GET /api/auth/me': {
      body: { user: signedIn[role], org: devOrg },
    },
    'GET /api/incidents/7': { body: incident },
    'GET /api/incidents/7/postmortem': {
      status: 404,
      body: { status: 404, message: 'Postmortem not found' },
    },
    'GET /api/incidents/7/follow-ups': { body: [] },
    ...extraRoutes,
  })

  const router = createMemoryRouter(routes, {
    initialEntries: ['/incidents/7/postmortem'],
  })

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

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

  return { fetchMock, queryClient }
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Postmortem editor and follow-ups (#245)', () => {
  it.each(['ENGINEER', 'ADMIN'] as const)('lets an %s fill all six fields with an editable AI draft and save explicitly', async (role) => {
    const user = userEvent.setup()
    const sections = {
      summary: 'AI checkout summary',
      impact: 'AI customer impact',
      rootCause: 'Suspected gateway failure',
      resolution: 'AI resolution notes',
      wentWell: 'AI detection notes',
      wentBadly: 'AI escalation notes',
    }
    const { fetchMock } = renderPostmortem(role, {
      'GET /api/incidents/7/postmortem': { body: draft },
      'POST /api/incidents/7/drafts': {
        body: {
          kind: 'POSTMORTEM',
          text: 'AI overview',
          postmortem: sections,
          generatedAt: '2026-11-20T08:00:00Z',
        },
      },
      'PUT /api/incidents/7/postmortem': {
        body: { ...draft, ...sections, summary: 'Reviewed summary', version: 2 },
      },
    })

    await user.click(await screen.findByRole('button', { name: 'Draft postmortem (AI)' }))

    await waitFor(() => expect(screen.getByLabelText('Summary')).toHaveValue(sections.summary))
    for (const [label, value] of Object.entries({
      Summary: sections.summary,
      Impact: sections.impact,
      'Root cause': sections.rootCause,
      Resolution: sections.resolution,
      'What went well': sections.wentWell,
      'What went badly': sections.wentBadly,
    })) {
      expect(screen.getByLabelText(label)).toHaveValue(value)
      expect(screen.getByLabelText(label)).toBeEnabled()
    }
    const request = fetchMock.mock.calls.find(([url, init]) =>
      String(url).endsWith('/api/incidents/7/drafts') && init?.method === 'POST',
    )
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ kind: 'POSTMORTEM' })
    expect(fetchMock.mock.calls.some(([url, init]) =>
      String(url).endsWith('/api/incidents/7/postmortem') && init?.method === 'PUT',
    )).toBe(false)

    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'Reviewed summary' } })
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => {
      const save = fetchMock.mock.calls.find(([url, init]) =>
        String(url).endsWith('/api/incidents/7/postmortem') && init?.method === 'PUT',
      )
      expect(JSON.parse(String(save?.[1]?.body))).toEqual({
        ...sections,
        summary: 'Reviewed summary',
        expectedVersion: 1,
      })
    })
  })

  it('shows the panel AI-unavailable message on 503 and preserves the postmortem form', async () => {
    const user = userEvent.setup()
    const { fetchMock } = renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/postmortem': { body: draft },
      'POST /api/incidents/7/drafts': {
        status: 503,
        body: { status: 503, message: 'AI service unavailable' },
      },
    })

    await user.click(await screen.findByRole('button', { name: 'Draft postmortem (AI)' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'AI is currently unavailable. You can still use the rest of the incident page.',
    )
    expect(screen.getByLabelText('Summary')).toHaveValue(draft.summary)
    expect(screen.getByLabelText('Summary')).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
  })

  it('preserves the form when the AI response has no postmortem fields', async () => {
    const user = userEvent.setup()
    renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/postmortem': { body: draft },
      'POST /api/incidents/7/drafts': {
        body: {
          kind: 'POSTMORTEM',
          text: 'Overview only',
          postmortem: null,
          generatedAt: '2026-11-20T08:00:00Z',
        },
      },
    })

    await user.click(await screen.findByRole('button', { name: 'Draft postmortem (AI)' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not generate the postmortem draft. Please try again.')
    expect(screen.getByLabelText('Summary')).toHaveValue(draft.summary)
  })

  it('sends all six postmortem fields when saving a draft', async () => {
    const user = userEvent.setup()

    const { fetchMock } = renderPostmortem('ENGINEER', {
      'PUT /api/incidents/7/postmortem': { body: draft },
    })

    const values = {
      Summary: 'Checkout outage',
      Impact: 'Payments failed for customers',
      'Root cause': 'Payment gateway configuration',
      Resolution: 'Restored the previous configuration',
      'What went well': 'Monitoring detected the issue',
      'What went badly': 'Escalation was delayed',
    }

    // Pasting, not typing: typing about 180 characters one key at a time ran past the 5 s test timeout.
    for (const [label, value] of Object.entries(values)) {
      await user.click(await screen.findByLabelText(label))
      await user.paste(value)
    }

    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url, init]) =>
        String(url).endsWith('/api/incidents/7/postmortem') &&
        init?.method === 'PUT',
      )

      expect(call).toBeDefined()

      const body = JSON.parse(String(call?.[1]?.body))

      expect(body).toMatchObject({
        summary: values.Summary,
        impact: values.Impact,
        rootCause: values['Root cause'],
        resolution: values.Resolution,
        wentWell: values['What went well'],
        wentBadly: values['What went badly'],
        expectedVersion: null,
      })
    })
  })

  it('does not show save or add controls to a Viewer', async () => {
    renderPostmortem('VIEWER')

    expect(
      await screen.findByText('You have read-only access.'),
    ).toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Save draft' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Draft postmortem (AI)' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Add follow-up' }),
    ).not.toBeInTheDocument()
  })

  it('flags overdue follow-ups', async () => {
    renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/follow-ups': {
        body: [
          {
            ...followUp,
            dueDate: '2020-01-01',
            overdue: true,
          },
        ],
      },
    })

    expect(
      await screen.findByText('Update gateway monitoring'),
    ).toBeInTheDocument()

    expect(screen.getByText('Overdue')).toBeInTheDocument()
  })

  it('rejects a past due date without sending a follow-up request', async () => {
    const user = userEvent.setup()
    const { fetchMock } = renderPostmortem('ENGINEER')

    await user.type(
      await screen.findByLabelText('Follow-up title'),
      'Update gateway monitoring',
    )

    const dateInput = document.querySelector('input[type="date"]')

    expect(dateInput).not.toBeNull()

    fireEvent.change(dateInput!, {
      target: { value: '2000-01-01' },
    })

    await user.click(
      screen.getByRole('button', { name: 'Add follow-up' }),
    )

    expect(
      await screen.findByText('Due date cannot be in the past.'),
    ).toBeInTheDocument()

    const sentFollowUp = fetchMock.mock.calls.some(([url, init]) =>
      String(url).endsWith('/api/incidents/7/follow-ups') &&
      init?.method === 'POST',
    )

    expect(sentFollowUp).toBe(false)
  })

  it('sends the loaded version when requesting a review', async () => {
    const user = userEvent.setup()

    const reviewedDraft = {
      ...draft,
      status: 'REVIEWED',
      version: 2,
      reviewedBy: { id: 1, displayName: 'Dev Admin' },
      reviewedAt: '2026-11-18T08:00:00Z',
    }

    const { fetchMock } = renderPostmortem('ADMIN', {
      'GET /api/incidents/7/postmortem': { body: draft },
      'POST /api/incidents/7/postmortem/review': {
        body: reviewedDraft,
      },
    })

    await user.click(
      await screen.findByRole('button', { name: 'Mark reviewed' }),
    )

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url, init]) =>
        String(url).endsWith('/api/incidents/7/postmortem/review') &&
        init?.method === 'POST',
      )

      expect(call).toBeDefined()
      expect(JSON.parse(String(call?.[1]?.body))).toEqual({
        expectedVersion: 1,
      })
    })
  })

  it('does not offer review to the postmortem author', async () => {
    const { fetchMock } = renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/postmortem': { body: draft },
    })

    expect(
      await screen.findByText('Checkout outage'),
    ).toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Mark reviewed' }),
    ).not.toBeInTheDocument()

    expect(
      screen.getByText(
        'Someone other than the author must review this postmortem.',
      ),
    ).toBeInTheDocument()

    const reviewRequest = fetchMock.mock.calls.some(([url]) =>
      String(url).endsWith('/api/incidents/7/postmortem/review'),
    )

    expect(reviewRequest).toBe(false)
  })

  it('warns that saving a reviewed postmortem sends it back for review', async () => {
    renderPostmortem('ADMIN', {
      'GET /api/incidents/7/postmortem': {
        body: { ...draft, status: 'REVIEWED', reviewedBy: { id: 1, displayName: 'Dev Admin' }, reviewedAt: '2026-11-13T09:00:00Z' },
      },
    })

    expect(await screen.findByText(/Saving changes sends it back to draft/)).toBeInTheDocument()
    // Each field is labelled, with the field name as its id.
    expect(screen.getByLabelText('Summary')).toHaveValue('Checkout outage')
    expect(screen.getByLabelText('Root cause')).toHaveAttribute('id', 'rootCause')
  })

  it('reloads the latest saved text and explains a 409 save conflict', async () => {
    const user = userEvent.setup()
    let getCount = 0

    const latestDraft = {
      ...draft,
      summary: 'Latest summary saved by another user',
      version: 2,
    }

    const { fetchMock } = renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/postmortem': () => {
        getCount += 1

        return {
          body: getCount === 1 ? draft : latestDraft,
        }
      },
      'PUT /api/incidents/7/postmortem': {
        status: 409,
        body: { status: 409, message: 'Version conflict' },
      },
    })

    const summary = await screen.findByLabelText('Summary')

    await user.clear(summary)
    await user.type(summary, 'My conflicting summary')

    await user.click(
      screen.getByRole('button', { name: 'Save draft' }),
    )

    expect(
      await screen.findByText(/latest saved version has been reloaded/i),
    ).toBeInTheDocument()

    await waitFor(() => {
      expect(summary).toHaveValue(
        'Latest summary saved by another user',
      )
    })

    expect(getCount).toBeGreaterThanOrEqual(2)

    const saveCall = fetchMock.mock.calls.find(([url, init]) =>
      String(url).endsWith('/api/incidents/7/postmortem') &&
      init?.method === 'PUT',
    )

    expect(saveCall).toBeDefined()

    expect(
      JSON.parse(String(saveCall?.[1]?.body)).expectedVersion,
    ).toBe(1)
  })

  it('adds a follow-up with the entered details', async () => {
    const user = userEvent.setup()

    const { fetchMock } = renderPostmortem('ENGINEER', {
      'POST /api/incidents/7/follow-ups': {
        body: followUp,
      },
    })

    await user.type(
      await screen.findByLabelText('Follow-up title'),
      'Update gateway monitoring',
    )

    fireEvent.change(document.querySelector('input[type="date"]')!, {
      target: { value: '2099-01-01' },
    })

    await user.click(
      screen.getByRole('button', { name: 'Add follow-up' }),
    )

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url, init]) =>
        String(url).endsWith('/api/incidents/7/follow-ups') &&
        init?.method === 'POST',
      )

      expect(call).toBeDefined()

      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        title: 'Update gateway monitoring',
        kind: 'CORRECTIVE',
        ownerId: 2,
        dueDate: '2099-01-01',
      })
    })

    expect(await screen.findByText('Follow-up added.')).toBeInTheDocument()
  })

  it('changes a follow-up status', async () => {
    const user = userEvent.setup()

    const { fetchMock } = renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/follow-ups': {
        body: [followUp],
      },
      'PUT /api/follow-ups/11': {
        body: {
          ...followUp,
          status: 'DONE',
          completedAt: '2026-11-18T08:00:00Z',
        },
      },
    })

    const title = await screen.findByText('Update gateway monitoring')
    const followUpCard = title.closest('div.rounded-lg.border')

    expect(followUpCard).not.toBeNull()

    const statusSelect = within(followUpCard as HTMLElement).getByRole('combobox')

    await user.click(statusSelect)

    await user.click(
      await screen.findByRole('option', { name: 'Done' }),
    )

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url, init]) =>
        String(url).endsWith('/api/follow-ups/11') &&
        init?.method === 'PUT',
      )

      expect(call).toBeDefined()

      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        status: 'DONE',
        title: followUp.title,
        ownerId: 2,
      })
    })
  })
})
