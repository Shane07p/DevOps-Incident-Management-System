
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
  description: 'Card payments fail.',
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
  deploys: [],
}

type Routes = Parameters<typeof mockFetch>[0]

function renderPage(
  role: Role,
  changes: Record<string, unknown> = {},
  extraRoutes: Routes = {},
) {
  writeToken('test-token')

  const fetchMock = mockFetch({
    'GET /api/auth/me': {
      body: { user: signedIn[role], org: devOrg },
    },
    'GET /api/incidents/7': {
      body: { ...incident, ...changes },
    },
    'GET /api/telemetry/logs': { body: [] },
    'GET /api/telemetry/metrics': { body: [] },
    ...extraRoutes,
  })

  const router = createMemoryRouter(routes, {
    initialEntries: ['/incidents/7'],
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

  return fetchMock
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Incident detail Sprint 4', () => {
  it('shows empty states for deploys, logs, metrics and evidence', async () => {
    renderPage('VIEWER')

    expect(
      await screen.findByText(
        'No deploys found in the 2 hours before this incident.',
      ),
    ).toBeInTheDocument()

    expect(
      await screen.findByText(
        "No logs found for this incident's time window.",
      ),
    ).toBeInTheDocument()

    expect(
      await screen.findByText('No evidence links added yet.'),
    ).toBeInTheDocument()

    expect(
      await screen.findByText('No request rate data available.'),
    ).toBeInTheDocument()
  })

  it('hides Link and Add evidence actions from Viewers', async () => {
    renderPage('VIEWER', {
      deploys: [
        {
          id: 12,
          version: 'v2.0',
          commit: 'abc123',
          author: 'Dev Engineer',
          deployedAt: '2026-10-20T07:30:00Z',
        },
      ],
    })

    await screen.findByText('v2.0')

    expect(
      screen.queryByRole('button', { name: 'Link' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Add evidence' }),
    ).not.toBeInTheDocument()
  })

  it.each(['ENGINEER', 'ADMIN'] as const)(
    'allows %s to link a deploy',
    async (role) => {
      const fetchMock = renderPage(
        role,
        {
          deploys: [
            {
              id: 12,
              version: 'v2.0',
              commit: 'abc123',
              author: 'Dev Engineer',
              deployedAt: '2026-10-20T07:30:00Z',
            },
          ],
        },
        {
          'POST /api/incidents/7/deploys/12': {
            status: 204,
          },
        },
      )

      await userEvent.click(
        await screen.findByRole('button', { name: 'Link' }),
      )

      await waitFor(() => {
        expect(
          fetchMock.mock.calls.some(
            ([url, init]) =>
              String(url).includes('/api/incidents/7/deploys/12') &&
              init?.method === 'POST',
          ),
        ).toBe(true)
      })
    },
  )

  it('allows Engineers to add evidence links', async () => {
    const fetchMock = renderPage('ENGINEER', {}, {
      'POST /api/incidents/7/evidence': {
        status: 204,
      },
    })

    await screen.findByText('Evidence Links')

    await userEvent.type(
      screen.getByLabelText('Label'),
      'Error logs',
    )

    await userEvent.type(
      screen.getByLabelText('URL'),
      'https://example.com/logs',
    )

    await userEvent.click(
      screen.getByRole('button', { name: 'Add evidence' }),
    )

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) =>
            String(url).includes('/api/incidents/7/evidence') &&
            init?.method === 'POST',
        ),
      ).toBe(true)
    })
  })

  it('shows recent deploy details', async () => {
    renderPage('VIEWER', {
      deploys: [
        {
          id: 12,
          version: 'v2.0',
          commit: 'abc123',
          author: 'Dev Engineer',
          deployedAt: '2026-10-20T07:30:00Z',
        },
      ],
    })

    expect(await screen.findByText('v2.0')).toBeInTheDocument()
    expect(screen.getByText('Commit: abc123')).toBeInTheDocument()
  })

  it('shows telemetry errors without breaking the incident page', async () => {
    renderPage('VIEWER', {}, {
      'GET /api/telemetry/logs': { status: 503 },
      'GET /api/telemetry/metrics': { status: 503 },
    })

    expect(
      await screen.findByText(
        'Unable to load logs. Telemetry may be unavailable.',
      ),
    ).toBeInTheDocument()

    expect(
      screen.getByText('Checkout failing'),
    ).toBeInTheDocument()

    expect(
      await screen.findByText('Unable to load request rate.'),
    ).toBeInTheDocument()
  })
})
