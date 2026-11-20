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
  status: 'INVESTIGATING',
  version: 3,
  reporter: { id: 2, displayName: 'Dev Engineer' },
  assignee: { id: 2, displayName: 'Dev Engineer' },
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
  // Note: color-contrast rule is turned off in jsdom (see expectNoSeriousAxeViolations) because
  // jsdom does not calculate layout geometry or rendered CSS styles. Contrast compliance across
  // light and dark themes is verified separately via browser audits (axe DevTools / Lighthouse).
  it.each(['light', 'dark'] as const)(
    'home and app shell have no serious or critical violations in %s theme (excluding contrast)',
    async (theme) => {
      renderAccessibilityPage('/home', theme)
      await screen.findByRole('region', { name: /Waiting for acknowledgement/ })

      await expectNoSeriousAxeViolations()
    },
    15000,
  )

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

  it('navigation drawer traps focus on tab and restores focus on Escape', async () => {
    renderAccessibilityPage('/home')
    await screen.findByRole('region', { name: /Waiting for acknowledgement/ })

    const menuButton = screen.getByRole('button', { name: 'Open navigation' })
    menuButton.focus()
    await userEvent.keyboard('{Enter}')

    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    expect(drawer).toContainElement(document.activeElement as HTMLElement)

    // Tab through controls inside the drawer; focus must remain inside
    for (let i = 0; i < 8; i++) {
      await userEvent.tab()
      expect(drawer).toContainElement(document.activeElement as HTMLElement)
    }

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Navigation' })).not.toBeInTheDocument())
    expect(menuButton).toHaveFocus()
  })

  it('app dialogs (Change status on incident page) trap focus and restore focus on Escape', async () => {
    renderAccessibilityPage('/incidents/7')
    await screen.findByRole('heading', { name: 'Checkout failing' })

    const changeStatusButton = await screen.findByRole('button', { name: 'Change status' })
    changeStatusButton.focus()
    await userEvent.keyboard('{Enter}')

    const dialog = await screen.findByRole('dialog', { name: 'Change status' })
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    // Tab through and past the last control; focus must stay trapped inside dialog
    for (let i = 0; i < 8; i++) {
      await userEvent.tab()
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Change status' })).not.toBeInTheDocument())
    expect(changeStatusButton).toHaveFocus()
  })

  it('interactive elements on home have visible focus ring styles when tabbed', async () => {
    renderAccessibilityPage('/home')
    await screen.findByRole('region', { name: /Waiting for acknowledgement/ })

    // Tab through interactive elements and verify each focused element carries visible focus styles
    for (let i = 0; i < 6; i++) {
      await userEvent.tab()
      const el = document.activeElement as HTMLElement
      expect(el).not.toBe(document.body)
      const className = el.className || ''
      const hasFocusRing =
        className.includes('focus:') ||
        className.includes('focus-visible:') ||
        className.includes('ring') ||
        className.includes('outline')
      expect(hasFocusRing).toBe(true)
    }
  })
})
