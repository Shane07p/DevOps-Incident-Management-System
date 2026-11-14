import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch } from '@/test/fetchMock'

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

function renderMap() {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: viewer, org: devOrg } },
    'GET /api/service-map': { body: map },
    'GET /api/services/1/blast-radius': { body: [{ id: 2, name: 'Checkout' }, { id: 3, name: 'Storefront' }] },
    'GET /api/services/4/blast-radius': { body: [] },
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
})
