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

const published = { ...payments, publicVisible: true, publicName: 'Card payments', telemetryLabel: 'cards' }
const hidden = { ...payments, id: 2, name: 'Checkout', description: null, publicVisible: false, publicName: null, telemetryLabel: null }
const publicStatus = {
  orgName: devOrg.name,
  services: [{ id: 1, name: 'Card payments', health: 'DEGRADED', uptimePercent: 99.5, uptimeDays: [] }],
  activeIncidents: [{
    id: 7, serviceName: 'Card payments', status: 'INVESTIGATING', resolvedAt: null,
    updates: [
      { id: 2, status: 'INVESTIGATING', message: 'We are investigating a processor delay.', createdAt: '2026-11-21T06:00:00Z' },
      { id: 1, status: 'INVESTIGATING', message: 'An older update.', createdAt: '2026-11-21T05:00:00Z' },
    ],
  }],
  resolvedIncidents: [],
}
const statusRoute = `GET /api/public/status/${devOrg.slug}`
type MockRoutes = Parameters<typeof mockFetch>[0]

function renderPage(role: Role = 'ADMIN', overrides: MockRoutes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: { ...users[0], role }, org: devOrg } },
    'GET /api/services': { body: [published, hidden] },
    [statusRoute]: { body: publicStatus },
    'GET /api/notifications/unread-count': { body: { count: 0 } },
    ...overrides,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const router = createMemoryRouter(routes, { initialEntries: ['/status-pages'] })
  render(
    <ThemeProvider>
      <QueryClientProvider client={client}>
        <TooltipProvider><AuthProvider><RouterProvider router={router} /></AuthProvider></TooltipProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return fetchMock
}

const callsTo = (fetchMock: ReturnType<typeof mockFetch>, method: string, path: string) =>
  fetchMock.mock.calls.filter(([input, init]) => (init?.method ?? 'GET') === method && String(input) === path)

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Status pages screen (#164)', () => {
  it('shows the real route and org URL, copies it, and opens it in a new tab', async () => {
    const user = userEvent.setup()
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Status pages' })).toBeInTheDocument()
    const panel = screen.getByRole('region', { name: 'Your status page' })
    const url = `${window.location.origin}/status/${devOrg.slug}`
    expect(within(panel).getByText(url)).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Copy' }))
    expect(await navigator.clipboard.readText()).toBe(url)
    expect(within(panel).getByRole('button', { name: 'Copied' })).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'Open' })).toHaveAttribute('href', url)
    expect(within(panel).getByRole('link', { name: 'Open' })).toHaveAttribute('target', '_blank')
    expect(screen.getByRole('link', { name: "incident's page" })).toHaveAttribute('href', '/incidents')
  })

  it('renders public health, service count and only the latest public update', async () => {
    renderPage()
    expect(await screen.findByText('Partial outage')).toBeInTheDocument()
    expect(screen.getByText('1 service shown')).toBeInTheDocument()
    const incidents = screen.getByRole('list', { name: 'Active public incidents' })
    expect(within(incidents).getByText('Card payments')).toBeInTheDocument()
    expect(within(incidents).getByText('Investigating')).toBeInTheDocument()
    expect(within(incidents).getByText('We are investigating a processor delay.')).toBeInTheDocument()
    expect(within(incidents).getByText('21 Nov, 11:30 IST')).toBeInTheDocument()
    expect(within(incidents).queryByText('An older update.')).not.toBeInTheDocument()
  })

  it('lets an Admin show a service, preserves the full payload, and refetches both queries', async () => {
    const user = userEvent.setup()
    let saved = false
    const fetchMock = renderPage('ADMIN', {
      'GET /api/services': () => ({ body: [published, { ...hidden, publicVisible: saved, publicName: saved ? 'Checkout' : null }] }),
      [statusRoute]: () => ({ body: { ...publicStatus, services: saved ? [...publicStatus.services, { ...publicStatus.services[0], id: 2, name: 'Checkout' }] : publicStatus.services } }),
      'PUT /api/services/2': () => { saved = true; return { body: { ...hidden, publicVisible: true, publicName: 'Checkout' } } },
    })
    await user.click(await screen.findByRole('switch', { name: 'Shown: Checkout' }))
    await waitFor(() => expect(callsTo(fetchMock, 'PUT', '/api/services/2')).toHaveLength(1))
    const body = JSON.parse(String(callsTo(fetchMock, 'PUT', '/api/services/2')[0][1]?.body))
    expect(body).toEqual({ name: hidden.name, description: '', ownerId: hidden.owner.id, criticality: hidden.criticality, targets: hidden.targets, telemetryLabel: '', publicVisible: true, publicName: 'Checkout' })
    expect(await screen.findByText('2 services shown')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Shown: Checkout' })).toBeChecked()
    expect(callsTo(fetchMock, 'GET', '/api/services').length).toBeGreaterThanOrEqual(2)
    expect(callsTo(fetchMock, 'GET', `/api/public/status/${devOrg.slug}`).length).toBeGreaterThanOrEqual(2)
  })

  it('saves an inline public name without changing the internal name, telemetry label or SLA targets', async () => {
    const user = userEvent.setup()
    let saved = false
    const fetchMock = renderPage('ADMIN', {
      'GET /api/services': () => ({ body: [{ ...published, publicName: saved ? 'Customer payments' : published.publicName }] }),
      'PUT /api/services/1': () => { saved = true; return { body: { ...published, publicName: 'Customer payments' } } },
    })
    const input = await screen.findByRole('textbox', { name: 'Public name for Payments' })
    await user.clear(input)
    await user.type(input, 'Customer payments')
    await user.click(screen.getByRole('button', { name: 'Save public name for Payments' }))
    await waitFor(() => expect(callsTo(fetchMock, 'PUT', '/api/services/1')).toHaveLength(1))
    expect(JSON.parse(String(callsTo(fetchMock, 'PUT', '/api/services/1')[0][1]?.body))).toMatchObject({ name: 'Payments', publicName: 'Customer payments', publicVisible: true, telemetryLabel: 'cards', targets: published.targets, ownerId: published.owner.id })
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Public name for Payments' })).toHaveValue('Customer payments'))
  })

  it('uses the service name when an Admin clears the public name', async () => {
    const user = userEvent.setup()
    const fetchMock = renderPage('ADMIN', { 'PUT /api/services/1': { body: { ...published, publicName: 'Payments' } } })
    await user.clear(await screen.findByRole('textbox', { name: 'Public name for Payments' }))
    await user.click(screen.getByRole('button', { name: 'Save public name for Payments' }))
    await waitFor(() => expect(callsTo(fetchMock, 'PUT', '/api/services/1')).toHaveLength(1))
    expect(JSON.parse(String(callsTo(fetchMock, 'PUT', '/api/services/1')[0][1]?.body)).publicName).toBe('Payments')
  })

  it.each(['ENGINEER', 'VIEWER'] as const)('gives %s a read-only list with public-name fallback', async (role) => {
    renderPage(role)
    expect(await screen.findByRole('heading', { name: 'Checkout' })).toBeInTheDocument()
    expect(screen.getByText('Public name: Checkout')).toBeInTheDocument()
    expect(screen.getByText('Public name: Card payments')).toBeInTheDocument()
    expect(screen.getByText('Shown', { exact: true })).toBeInTheDocument()
    expect(screen.getByText('Hidden', { exact: true })).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save public name/ })).not.toBeInTheDocument()
  })

  it('shows the no-services state with a link to the catalog', async () => {
    renderPage('ADMIN', { 'GET /api/services': { body: [] } })
    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to services' })).toHaveAttribute('href', '/services')
  })

  it('explains when services exist but none are shown', async () => {
    renderPage('ADMIN', {
      'GET /api/services': { body: [hidden] },
      [statusRoute]: { body: { ...publicStatus, services: [], activeIncidents: [] } },
    })
    expect(await screen.findByText('Your status page lists no services')).toBeInTheDocument()
    expect(screen.getByText('Turn on Shown for a service below to put it on your page.')).toBeInTheDocument()
    expect(screen.getByText('No services shown')).toBeInTheDocument()
    expect(screen.getByText('No active public incidents.')).toBeInTheDocument()
    expect(screen.queryByText('All systems operational')).not.toBeInTheDocument()
  })

  it('keeps services usable when the public status API fails', async () => {
    renderPage('ADMIN', { [statusRoute]: { status: 503, body: { message: 'Status unavailable' } } })
    expect(await screen.findByText('Public status could not be loaded')).toBeInTheDocument()
    expect(screen.getByText('Status unavailable')).toBeInTheDocument()
    expect(await screen.findByRole('switch', { name: 'Shown: Checkout' })).toBeEnabled()
  })

  it('keeps the saved visibility and shows the server error if an update fails', async () => {
    const user = userEvent.setup()
    renderPage('ADMIN', { 'PUT /api/services/2': { status: 403, body: { message: 'Admin access is required' } } })
    await user.click(await screen.findByRole('switch', { name: 'Shown: Checkout' }))
    expect(await screen.findByText('Admin access is required')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Shown: Checkout' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Shown: Checkout' })).toBeEnabled()
  })

  it('shows a useful message if clipboard access fails', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('Denied'))
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Copy' }))
    expect(await screen.findByText('Could not copy the URL. Select it and copy it manually.')).toBeInTheDocument()
  })

  it('can retry the services request without losing the public summary', async () => {
    const user = userEvent.setup()
    let failed = true
    renderPage('ADMIN', {
      'GET /api/services': () => failed ? { status: 503, body: { message: 'Catalog unavailable' } } : { body: [published] },
    })
    expect(await screen.findByText('Catalog unavailable')).toBeInTheDocument()
    expect(await screen.findByText('Partial outage')).toBeInTheDocument()
    failed = false
    await user.click(within(screen.getByRole('region', { name: 'Services on the page' })).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('switch', { name: 'Shown: Payments' })).toBeInTheDocument()
  })

  it('lets an Admin hide a published service', async () => {
    const user = userEvent.setup()
    let visible = true
    const fetchMock = renderPage('ADMIN', {
      'GET /api/services': () => ({ body: [{ ...published, publicVisible: visible }] }),
      'PUT /api/services/1': () => { visible = false; return { body: { ...published, publicVisible: false } } },
    })
    await user.click(await screen.findByRole('switch', { name: 'Shown: Payments' }))
    await waitFor(() => expect(callsTo(fetchMock, 'PUT', '/api/services/1')).toHaveLength(1))
    expect(JSON.parse(String(callsTo(fetchMock, 'PUT', '/api/services/1')[0][1]?.body)).publicVisible).toBe(false)
    expect(await screen.findByText('Your status page lists no services')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Shown: Payments' })).not.toBeChecked()
  })
})
