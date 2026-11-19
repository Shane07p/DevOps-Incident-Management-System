import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, payments } from '@/test/fetchMock'

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' }

// Storefront needs Checkout, which needs Payments; Search stands alone.
const map = {
  nodes: [
    { id: 1, name: 'Payments', criticality: 'CRITICAL', health: 'DOWN' },
    { id: 2, name: 'Checkout', criticality: 'HIGH', health: 'DEGRADED' },
    { id: 3, name: 'Storefront', criticality: 'MEDIUM', health: 'OPERATIONAL' },
    { id: 4, name: 'Search', criticality: 'LOW', health: 'UNKNOWN' },
  ],
  edges: [
    { from: 2, to: 1 },
    { from: 3, to: 2 },
  ],
}

function renderMap(extra: Parameters<typeof mockFetch>[0] = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/service-map': { body: map },
    'GET /api/services/1/blast-radius': { body: [{ id: 2, name: 'Checkout' }, { id: 3, name: 'Storefront' }] },
    'GET /api/services/4/blast-radius': { body: [] },
    ...extra,
  })
  const router = createMemoryRouter(routes, { initialEntries: ['/service-map'] })
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
  return fetchMock
}

const node = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name},`) })

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('ServiceMapPage', () => {
  it('shows every service with its health, and who depends on whom', async () => {
    renderMap()

    expect(await screen.findByRole('button', { name: 'Payments, Down' })).toBeInTheDocument()
    expect(node('Checkout')).toHaveAccessibleName('Checkout, Degraded')
    expect(node('Storefront')).toHaveAccessibleName('Storefront, Operational')
    expect(node('Search')).toHaveAccessibleName('Search, Unknown')
    const links = within(screen.getByRole('list', { name: 'Dependencies' })).getAllByRole('listitem')
    expect(links.map((link) => link.textContent)).toEqual(['Checkout depends on Payments', 'Storefront depends on Checkout'])
  })

  it('TC-US-26a: in Blast radius, choosing a service highlights everything that depends on it, directly or not', async () => {
    renderMap()
    await userEvent.click(await screen.findByRole('radio', { name: 'Blast radius' }))
    await userEvent.click(screen.getByRole('button', { name: 'Payments, Down' }))

    expect(await screen.findByText(/If Payments fails, 2 services may be affected/)).toHaveTextContent('Checkout, Storefront')
    expect(node('Payments')).toHaveAttribute('aria-pressed', 'true')
    expect(node('Checkout')).toHaveAccessibleName('Checkout, Degraded, in the blast radius')
    expect(node('Storefront')).toHaveAccessibleName('Storefront, Operational, in the blast radius')
    expect(node('Search')).toHaveAccessibleName('Search, Unknown')
  })

  it('says so when nothing depends on the chosen service', async () => {
    renderMap()
    await userEvent.click(await screen.findByRole('radio', { name: 'Blast radius' }))
    await userEvent.click(screen.getByRole('button', { name: 'Search, Unknown' }))

    expect(await screen.findByText('Nothing depends on Search.')).toBeInTheDocument()
  })

  it('TC-US-26b: a Viewer gets the map with nothing to edit', async () => {
    renderMap()
    await screen.findByRole('button', { name: 'Payments, Down' })

    const main = screen.getByRole('main')
    const buttons = within(main).getAllByRole('button').map((button) => button.getAttribute('aria-label') ?? '')
    expect(buttons.every((label) => /^(Payments|Checkout|Storefront|Search),/.test(label))).toBe(true)
    expect(screen.queryByRole('button', { name: /edit|depends on|save/i })).not.toBeInTheDocument()
  })

  it('TC-US-26c: switches between Dependencies and Blast radius from the keyboard', async () => {
    renderMap()
    const dependencies = await screen.findByRole('radio', { name: 'Dependencies' })
    expect(dependencies).toHaveAttribute('aria-checked', 'true')

    dependencies.focus()
    await userEvent.keyboard('{ArrowRight}')
    const blast = screen.getByRole('radio', { name: 'Blast radius' })
    expect(blast).toHaveAttribute('aria-checked', 'true')
    expect(blast).toHaveFocus()

    // Only the chosen view is in the tab order, so Tab leaves the group for the map.
    await userEvent.tab()
    expect(screen.getByRole('radio', { name: 'Dependencies' })).not.toHaveFocus()
    node('Payments').focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByText(/If Payments fails, 2 services may be affected/)).toBeInTheDocument()

    await userEvent.click(blast)
    await userEvent.keyboard('{ArrowLeft}')
    expect(dependencies).toHaveAttribute('aria-checked', 'true')
    expect(node('Checkout')).toHaveAccessibleName('Checkout, Degraded')
  })

  it('TC-US-26d: in Dependencies, the chosen service shows what it calls and what calls it', async () => {
    renderMap()
    await userEvent.click(await screen.findByRole('button', { name: 'Checkout, Degraded' }))

    const panel = screen.getByRole('region', { name: 'Checkout' })
    expect(within(panel).getByRole('heading', { name: 'Calls' }).nextElementSibling).toHaveTextContent('Payments')
    expect(within(panel).getByRole('heading', { name: 'Called by' }).nextElementSibling).toHaveTextContent('Storefront')
    expect(within(panel).getByRole('link', { name: 'Open service' })).toHaveAttribute('href', '/services/2')

    await userEvent.click(within(panel).getByRole('button', { name: 'Clear selection' }))
    expect(node('Checkout')).toHaveAttribute('aria-pressed', 'false')
  })

  it('marks each service with its worst open incident and lists them, with the owner, when chosen', async () => {
    const open = (id: number, title: string, serviceName: string, severity: string, status = 'OPEN') => ({
      id, reference: `INC-${id}`, title, serviceName, severity, status, createdAt: `2026-11-1${id}T08:00:00Z`,
      responseDueAt: '2026-11-20T08:00:00Z', resolutionDueAt: '2026-11-21T08:00:00Z', version: 1,
    })
    renderMap({
      'GET /api/incidents': {
        body: [
          open(1, 'Card payments failing', 'Payments', 'SEV3'),
          open(2, 'Refunds stuck', 'Payments', 'SEV1'),
          open(3, 'Slow basket', 'Checkout', 'SEV2'),
          open(4, 'Typo in results', 'Search', 'SEV4'),
          open(5, 'Old outage', 'Storefront', 'SEV1', 'RESOLVED'),
        ],
      },
      'GET /api/services': { body: [{ ...payments, id: 1, owner: { id: 1, displayName: 'Dev Admin' } }] },
    })

    expect(await screen.findByRole('button', { name: 'Payments, Down, 2 open incidents' })).toHaveTextContent('SEV1 +1')
    expect(node('Checkout')).toHaveAccessibleName('Checkout, Degraded, 1 open incident')
    expect(node('Checkout')).toHaveTextContent('SEV2')
    expect(node('Search')).toHaveTextContent('SEV4')
    expect(node('Storefront')).toHaveAccessibleName('Storefront, Operational')

    await userEvent.click(node('Payments'))
    const panel = screen.getByRole('region', { name: 'Payments' })
    const incidents = within(panel).getByRole('heading', { name: 'Open incidents' }).nextElementSibling as HTMLElement
    expect(within(incidents).getAllByRole('link').map((link) => link.textContent)).toEqual(['Refunds stuck', 'Card payments failing'])
    expect(within(incidents).getByRole('link', { name: 'Refunds stuck' })).toHaveAttribute('href', '/incidents/2')
    expect(panel).toHaveTextContent('Owner · Dev Admin')
  })

  it('draws services that call each other in a loop, a link that skips a column, and ignores a link to nothing', async () => {
    renderMap({
      'GET /api/service-map': {
        body: {
          nodes: [
            { id: 1, name: 'Payments', criticality: 'CRITICAL', health: 'DOWN' },
            { id: 2, name: 'Checkout', criticality: 'HIGH', health: 'DEGRADED' },
            { id: 3, name: 'Storefront', criticality: 'MEDIUM', health: 'OPERATIONAL' },
            { id: 5, name: 'Ledger', criticality: 'HIGH', health: 'OPERATIONAL' },
            { id: 6, name: 'Audit', criticality: 'LOW', health: 'OPERATIONAL' },
          ],
          edges: [
            { from: 3, to: 2 },
            { from: 2, to: 1 },
            { from: 3, to: 1 },
            { from: 5, to: 6 },
            { from: 6, to: 5 },
            { from: 2, to: 99 },
          ],
        },
      },
    })

    expect(await screen.findByRole('button', { name: 'Ledger, Operational' })).toBeInTheDocument()
    expect(node('Audit')).toBeInTheDocument()
    const links = within(screen.getByRole('list', { name: 'Dependencies' })).getAllByRole('listitem').map((link) => link.textContent)
    expect(links).toEqual(expect.arrayContaining(['Storefront depends on Payments', 'Ledger depends on Audit', 'Audit depends on Ledger']))
    expect(links.join(' ')).not.toMatch(/undefined|99/)
  })

  it('says when there are no services, and shows a failed load with a retry', async () => {
    renderMap({ 'GET /api/service-map': { body: { nodes: [], edges: [] } } })
    expect(await screen.findByText(/No services yet/)).toBeInTheDocument()
    cleanup()
    clearToken()

    renderMap({ 'GET /api/service-map': { status: 500, body: { status: 500, message: 'The service map is unavailable' } } })
    expect(await screen.findByText('The service map is unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('in Blast radius, says one service when one may break, and shows a failed lookup', async () => {
    renderMap({
      'GET /api/services/2/blast-radius': { body: [{ id: 3, name: 'Storefront' }] },
      'GET /api/services/3/blast-radius': { status: 500, body: { status: 500, message: 'Could not work out the blast radius' } },
    })
    await userEvent.click(await screen.findByRole('radio', { name: 'Blast radius' }))

    await userEvent.click(node('Checkout'))
    expect(await screen.findByText(/If Checkout fails, 1 service may be affected/)).toHaveTextContent('Storefront')

    await userEvent.click(node('Storefront'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not work out the blast radius')
  })
})
