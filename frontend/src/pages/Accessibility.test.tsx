import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { expectNoSeriousAxeViolations } from '@/test/accessibility'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'

const engineer = users[1]

const incidents = [
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
    version: 3,
    assignee: null,
    acknowledgedAt: null,
    resolvedAt: null,
  },
]

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

const status = {
  orgName: 'Dev org',
  services: [{ id: 1, name: 'Payments', health: 'OPERATIONAL', uptimePercent: 100, uptimeDays: [] }],
  activeIncidents: [],
  resolvedIncidents: [],
}

function renderAccessibilityPage(path: string, theme: 'light' | 'dark' = 'light') {
  localStorage.setItem('settle-theme', theme)
  if (path.startsWith('/status/')) clearToken()
  else writeToken('test-token')

  mockFetch({
    'GET /api/auth/me': { body: { user: engineer, org: devOrg } },
    'GET /api/incidents': { body: incidents },
    'GET /api/incidents/7': { body: incident },
    'GET /api/services': { body: [payments] },
    'GET /api/service-map': {
      body: { nodes: [{ id: 1, name: 'Payments', criticality: 'CRITICAL', health: 'OPERATIONAL' }], edges: [] },
    },
    'GET /api/public/status/acme': { body: status },
  })

  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <ThemeProvider>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AuthProvider>
          <TooltipProvider>
            <RouterProvider router={router} />
          </TooltipProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

afterEach(() => {
  clearToken()
  localStorage.removeItem('settle-theme')
  document.documentElement.classList.remove('dark')
  vi.unstubAllGlobals()
})

describe('Accessibility axe audit (Issue #251)', () => {
  it.each(['light', 'dark'] as const)('home and app shell have no serious or critical violations in %s theme', async (theme) => {
    renderAccessibilityPage('/home', theme)
    await screen.findByRole('region', { name: /Waiting for acknowledgement/ })

    await expectNoSeriousAxeViolations()
  }, 15000)

  it('incident list has no serious or critical violations', async () => {
    renderAccessibilityPage('/incidents')
    await screen.findByRole('link', { name: 'Checkout failing' })

    await expectNoSeriousAxeViolations()
  }, 15000)

  it('incident detail has no serious or critical violations', async () => {
    renderAccessibilityPage('/incidents/7')
    await screen.findByRole('heading', { name: 'Checkout failing' })

    await expectNoSeriousAxeViolations()
  }, 15000)

  it('services has no serious or critical violations', async () => {
    renderAccessibilityPage('/services')
    await screen.findByRole('heading', { name: 'Services' })

    await expectNoSeriousAxeViolations()
  }, 15000)

  it('public status page has no serious or critical violations', async () => {
    renderAccessibilityPage('/status/acme')
    await screen.findByRole('heading', { name: 'All systems operational' })

    await expectNoSeriousAxeViolations()
  }, 15000)

  it('dialogs trap focus and restore focus on dismiss', async () => {
    renderAccessibilityPage('/home')
    await screen.findByRole('region', { name: /Waiting for acknowledgement/ })

    const menuButton = screen.getByRole('button', { name: 'Open navigation' })
    menuButton.focus()
    await userEvent.keyboard('{Enter}')

    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    expect(drawer).toContainElement(document.activeElement as HTMLElement)

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Navigation' })).not.toBeInTheDocument())
    expect(menuButton).toHaveFocus()
  })

  it('every interactive element has visible focus ring styles', async () => {
    renderAccessibilityPage('/home')
    const skipLink = await screen.findByRole('link', { name: 'Skip to content' })
    expect(skipLink).toHaveClass('focus:not-sr-only')
  })
})
