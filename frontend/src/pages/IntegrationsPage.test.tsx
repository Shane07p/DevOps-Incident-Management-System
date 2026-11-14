import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { IntegrationsPage } from './IntegrationsPage'

const notConnected = {
  connected: false, prometheusUrl: null, prometheusUser: null, lokiUrl: null, lokiUser: null, tokenSaved: false,
  serviceLabel: 'service', requestsMetric: 'http_requests_total', statusLabel: 'status',
  durationMetric: 'http_request_duration_seconds', updatedAt: null, updatedBy: null,
}

const connected = {
  ...notConnected, connected: true, tokenSaved: true,
  prometheusUrl: 'https://prometheus.example.net/api/prom', prometheusUser: '123456',
  lokiUrl: 'https://logs.example.net', lokiUser: '654321',
  updatedAt: '2026-11-13T04:30:00Z', updatedBy: 'Dev Admin',
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <IntegrationsPage />
    </QueryClientProvider>,
  )
}

const bodyOf = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  JSON.parse(String(fetchMock.mock.calls.find(([, init]) => init?.method === method)?.[1]?.body))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('IntegrationsPage', () => {
  it('connects Grafana: the first save needs a token, and the naming starts at the defaults', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({
      'GET /api/settings/telemetry': { body: notConnected },
      'PUT /api/settings/telemetry': { body: connected },
    })
    renderPage()

    expect(await screen.findByText(/Not connected/)).toBeInTheDocument()
    expect(screen.getByLabelText('Service label')).toHaveValue('service')
    await user.type(screen.getByLabelText('Loki URL'), 'https://logs.example.net')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Enter the read-only token')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)

    await user.type(screen.getByLabelText('Read-only token'), 'glc_secret')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Connected by Dev Admin/)).toBeInTheDocument()
    expect(bodyOf(fetchMock, 'PUT')).toMatchObject({ lokiUrl: 'https://logs.example.net', token: 'glc_secret', serviceLabel: 'service' })
    // The token is gone from the page once saved.
    expect(screen.getByLabelText('Read-only token')).toHaveValue('')
  })

  it('never shows the saved token, and an empty token field keeps it', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({
      'GET /api/settings/telemetry': { body: connected },
      'PUT /api/settings/telemetry': { body: connected },
    })
    renderPage()

    const token = await screen.findByLabelText('Read-only token')
    expect(token).toHaveValue('')
    expect(token).toHaveAttribute('placeholder', 'Saved. Leave empty to keep it.')
    expect(token).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true))
    expect(bodyOf(fetchMock, 'PUT')).toMatchObject({ token: '', prometheusUser: '123456' })
  })

  it('refuses names that are not Prometheus names, and a URL that is not one, before asking the server', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({ 'GET /api/settings/telemetry': { body: connected } })
    renderPage()

    const label = await screen.findByLabelText('Service label')
    await user.clear(label)
    await user.type(label, 'service"} or {{x="1')
    await user.clear(screen.getByLabelText('Loki URL'))
    await user.type(screen.getByLabelText('Loki URL'), 'logs.example.net')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Letters, digits, and _ only, not starting with a digit')).toBeInTheDocument()
    expect(screen.getByText('Enter a URL that starts with https://')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
  })

  it("shows the server's reason on the field it names", async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/settings/telemetry': { body: connected },
      'PUT /api/settings/telemetry': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { lokiUrl: 'The URL must not point to a private or local network address' } },
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByText('The URL must not point to a private or local network address')).toBeInTheDocument()
  })

  it('tests each store and says which one failed', async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/settings/telemetry': { body: connected },
      'POST /api/settings/telemetry/test': { body: { prometheus: 'ok', loki: 'Logs are unavailable right now' } },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Test connection' }))

    const results = await screen.findByRole('list', { name: 'Connection test' })
    expect(within(results).getByText('works')).toBeInTheDocument()
    expect(within(results).getByText('Logs are unavailable right now')).toBeInTheDocument()
  })

  it('disconnects only after a confirmation', async () => {
    const user = userEvent.setup()
    let deleted = false
    const fetchMock = mockFetch({
      'GET /api/settings/telemetry': () => ({ body: deleted ? notConnected : connected }),
      'DELETE /api/settings/telemetry': () => {
        deleted = true
        return { status: 204, body: null }
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Disconnect' }))
    const dialog = await screen.findByRole('dialog', { name: 'Disconnect Grafana?' })
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect' }))

    expect(await screen.findByText(/Not connected/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Test connection' })).not.toBeInTheDocument()
  })
})
