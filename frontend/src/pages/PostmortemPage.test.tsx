import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

function renderPostmortem(
  role: Role,
  extraRoutes: Record<string, { status?: number; body: unknown }> = {},
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
    defaultOptions: { queries: { retry: false } },
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

  return { fetchMock }
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Postmortem editor and follow-ups (#245)', () => {
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

    for (const [label, value] of Object.entries(values)) {
      await user.type(await screen.findByLabelText(label), value)
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

    expect(await screen.findByText('You have read-only access.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add follow-up' })).not.toBeInTheDocument()
  })

  it('flags overdue follow-ups', async () => {
    renderPostmortem('ENGINEER', {
      'GET /api/incidents/7/follow-ups': {
        body: [
          {
            id: 11,
            incidentId: 7,
            incidentReference: 'INC-7',
            title: 'Update gateway monitoring',
            kind: 'PREVENTIVE',
            owner: { id: 2, displayName: 'Dev Engineer' },
            dueDate: '2020-01-01',
            status: 'OPEN',
            overdue: true,
            completedAt: null,
            createdAt: '2026-10-20T08:30:00Z',
          },
        ],
      },
    })

    expect(await screen.findByText('Update gateway monitoring')).toBeInTheDocument()
    expect(screen.getByText('Overdue')).toBeInTheDocument()
  })

  it('rejects a past due date without sending a follow-up request', async () => {
    const user = userEvent.setup()
    const { fetchMock } = renderPostmortem('ENGINEER')

    await user.type(
      await screen.findByPlaceholderText('Follow-up title'),
      'Update gateway monitoring',
    )
    await user.type(screen.getByPlaceholderText('Owner ID'), '2')

    const dateInput = document.querySelector('input[type="date"]')
    expect(dateInput).not.toBeNull()
    fireEvent.change(dateInput!, {
      target: { value: '2000-01-01' },
    })

    await user.click(screen.getByRole('button', { name: 'Add follow-up' }))

    expect(await screen.findByText('Due date cannot be in the past.')).toBeInTheDocument()

    const sentFollowUp = fetchMock.mock.calls.some(([url, init]) =>
      String(url).endsWith('/api/incidents/7/follow-ups') &&
      init?.method === 'POST',
    )

    expect(sentFollowUp).toBe(false)
  })
})


