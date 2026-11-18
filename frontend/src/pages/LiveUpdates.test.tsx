import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { listIncidents } from '@/api/incidents'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

const engineerUser = users[1]

const baseIncident = {
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
    {
      id: 3,
      eventType: 'INCIDENT_CREATED',
      actor: 'Dev Engineer',
      details: 'Reported as SEV2 on Payments',
      recordedAt: '2026-10-20T08:30:00Z',
      correctsEntryId: null,
    },
  ],
}

const incidentSummaries = [
  {
    id: 7,
    reference: 'INC-7',
    title: 'Checkout failing',
    serviceName: 'Payments',
    severity: 'SEV2',
    status: 'OPEN',
    createdAt: '2026-10-20T08:30:00Z',
    responseDueAt: '2026-10-20T09:30:00Z',
    resolutionDueAt: '2026-10-20T16:30:00Z',
    version: 0,
  },
]

function IncidentListQueryProbe() {
  useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  return null
}

describe('Live updates pushed by the backend (FR-12.3, NFR-14, D46)', () => {
  afterEach(() => {
    clearToken()
    vi.unstubAllGlobals()
  })

  it('an event refetches the incident without a manual reload', async () => {
    writeToken('test-token')

    let dispatchEvent: (() => void) | undefined
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        dispatchEvent = () => controller.enqueue(new TextEncoder().encode('event: incident-changed\ndata: {"id": 7}\n\n'))
      },
    })

    let currentTitle = 'Checkout failing'
    let listRequests = 0
    let incidentRequests = 0

    mockFetch({
      'GET /api/auth/me': { body: { user: engineerUser, org: devOrg } },
      'GET /api/incidents': () => {
        listRequests += 1
        return { body: incidentSummaries.map((s) => ({ ...s, title: currentTitle })) }
      },
      'GET /api/incidents/7': () => {
        incidentRequests += 1
        return {
          body: {
            ...baseIncident,
            title: currentTitle,
          },
        }
      },
      'GET /api/services': { body: [payments] },
      'GET /api/services/1/runbooks': { body: [] },
      'GET /api/events': () =>
        new Response(stream, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }),
    })

    const router = createMemoryRouter(routes, { initialEntries: ['/incidents/7'] })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <IncidentListQueryProbe />
          <AuthProvider>
            <TooltipProvider>
              <RouterProvider router={router} />
            </TooltipProvider>
          </AuthProvider>
        </QueryClientProvider>
      </ThemeProvider>,
    )

    // Initial incident title rendered
    expect(await screen.findByText('Checkout failing')).toBeInTheDocument()

    // Remote update happens on backend
    currentTitle = 'Checkout failing (Mitigated)'

    // Backend pushes incident-changed event via SSE
    dispatchEvent?.()

    // The incident page refetches and displays the updated title
    await waitFor(() => {
      expect(screen.getByText('Checkout failing (Mitigated)')).toBeInTheDocument()
      expect(listRequests).toBe(2)
      expect(incidentRequests).toBe(2)
    })
  })

  it('the banner shows while the backend is unreachable and goes away when it answers again', async () => {
    writeToken('test-token')

    let isBackendDown = true

    mockFetch({
      'GET /api/auth/me': { body: { user: engineerUser, org: devOrg } },
      'GET /api/incidents': () => {
        if (isBackendDown) {
          return { status: 503, body: { message: 'Backend unreachable' } }
        }
        return {
          status: 200,
          body: incidentSummaries,
        }
      },
      'GET /api/events': () => {
        if (isBackendDown) return { status: 503, body: { message: 'Backend unreachable' } }
        return new Response(new ReadableStream<Uint8Array>({ start: () => undefined }), {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        })
      },
      'GET /api/services': { body: [payments] },
    })

    const router = createMemoryRouter(routes, { initialEntries: ['/incidents'] })
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

    // Banner is displayed while backend is unreachable
    expect(await screen.findByTestId('backend-unreachable-banner')).toBeInTheDocument()
    expect(screen.getByText(/The backend cannot be reached/i)).toBeInTheDocument()

    // Backend recovers
    isBackendDown = false

    // Refetch incidents
    await queryClient.refetchQueries({ queryKey: ['incidents'] })

    // Banner goes away
    await waitFor(() => {
      expect(screen.queryByTestId('backend-unreachable-banner')).not.toBeInTheDocument()
    }, { timeout: 5000 })
  })
})
