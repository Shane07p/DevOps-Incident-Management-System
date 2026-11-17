import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { RequireAuth } from '@/auth/RequireAuth'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'
import { MonitorsPage } from './MonitorsPage'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER' },
}

const healthy = {
  id: 7, name: 'Checkout health', url: 'https://shop.example.com/health', expectedStatus: 200, intervalSeconds: 60,
  serviceId: 1, serviceName: 'Payments', failureThreshold: 3, enabled: true, consecutiveFailures: 0,
  outageStartedAt: null, activeAlertId: null, lastCheckedAt: '2026-11-13T04:30:00Z', nextCheckAt: '2026-11-13T04:31:00Z',
  createdAt: '2026-11-01T04:30:00Z', updatedAt: '2026-11-01T04:30:00Z',
}
const down = {
  ...healthy, id: 8, name: 'Search health', url: 'https://shop.example.com/search', consecutiveFailures: 3,
  outageStartedAt: '2026-11-13T04:20:00Z', activeAlertId: 41,
}

type Routes = Parameters<typeof mockFetch>[0]

function renderMonitors(role: Role, routes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: signedIn[role], org: devOrg } },
    'GET /api/monitors': { body: [healthy, down] },
    'GET /api/services': { body: [payments] },
    ...routes,
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/monitors']}>
          <Routes>
            <Route element={<RequireAuth />}>
              <Route path="/monitors" element={<MonitorsPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return fetchMock
}

const bodyOf = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  JSON.parse(String(fetchMock.mock.calls.find(([, init]) => init?.method === method)?.[1]?.body))

afterEach(() => {
  vi.unstubAllGlobals()
  clearToken()
})

describe('MonitorsPage', () => {
  it("shows each monitor's real state, with no made-up history", async () => {
    renderMonitors('VIEWER')

    expect(await screen.findByRole('button', { name: 'Checkout health' })).toBeInTheDocument()
    expect(screen.getAllByText('Up').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/^Down since/).length).toBeGreaterThan(0)
    // No uptime bars or 30-day figures: no endpoint returns check history.
    expect(screen.queryByLabelText(/uptime bars/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/30 days/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New monitor' })).not.toBeInTheDocument()
  })

  it('creates a monitor with the numbers the server expects', async () => {
    const user = userEvent.setup()
    const fetchMock = renderMonitors('ADMIN', { 'POST /api/monitors': { status: 201, body: { ...healthy, id: 9 } } })

    await user.click(await screen.findByRole('button', { name: 'New monitor' }))
    const dialog = await screen.findByRole('dialog', { name: 'New monitor' })
    await user.type(within(dialog).getByLabelText('Name'), 'Orders')
    await user.type(within(dialog).getByLabelText('URL'), 'https://shop.example.com/orders')
    await user.click(within(dialog).getByRole('button', { name: 'Create monitor' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
    expect(bodyOf(fetchMock, 'POST')).toEqual({
      name: 'Orders', url: 'https://shop.example.com/orders', serviceId: 1, expectedStatus: 200,
      intervalSeconds: 60, failureThreshold: 3, enabled: true,
    })
  })

  it("refuses what the server would refuse, and shows the server's reason on its field", async () => {
    const user = userEvent.setup()
    const fetchMock = renderMonitors('ADMIN', {
      'POST /api/monitors': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { url: 'The URL must not point to a private or local network address' } },
      },
    })

    await user.click(await screen.findByRole('button', { name: 'New monitor' }))
    const dialog = await screen.findByRole('dialog', { name: 'New monitor' })
    await user.type(within(dialog).getByLabelText('Name'), 'Local')
    await user.type(within(dialog).getByLabelText('URL'), 'ftp://example.com')
    await user.clear(within(dialog).getByLabelText('Interval (seconds)'))
    await user.type(within(dialog).getByLabelText('Interval (seconds)'), '10')
    await user.click(within(dialog).getByRole('button', { name: 'Create monitor' }))

    expect(await within(dialog).findByText('Enter a valid http or https URL')).toBeInTheDocument()
    expect(within(dialog).getByText('Interval must be between 30 and 86400 seconds')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)

    await user.clear(within(dialog).getByLabelText('URL'))
    await user.type(within(dialog).getByLabelText('URL'), 'https://127.0.0.1/health')
    await user.clear(within(dialog).getByLabelText('Interval (seconds)'))
    await user.type(within(dialog).getByLabelText('Interval (seconds)'), '60')
    await user.click(within(dialog).getByRole('button', { name: 'Create monitor' }))

    expect(await within(dialog).findByText('The URL must not point to a private or local network address')).toBeInTheDocument()
  })

  it('edits the selected monitor with a PUT to its id', async () => {
    const user = userEvent.setup()
    const fetchMock = renderMonitors('ADMIN', { 'PUT /api/monitors/7': { body: { ...healthy, failureThreshold: 5 } } })

    await user.click(await screen.findByRole('button', { name: 'Edit Checkout health' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Checkout health' })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Checkout health')
    await user.clear(within(dialog).getByLabelText('Failures in a row'))
    await user.type(within(dialog).getByLabelText('Failures in a row'), '5')
    await user.click(within(dialog).getByRole('button', { name: 'Save monitor' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true))
    expect(bodyOf(fetchMock, 'PUT')).toMatchObject({ failureThreshold: 5, name: 'Checkout health' })
  })
})
