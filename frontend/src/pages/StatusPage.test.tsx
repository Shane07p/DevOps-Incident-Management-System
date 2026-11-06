import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken } from '@/api/token'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { routes } from '@/router'
import { mockFetch } from '@/test/fetchMock'

const baseStatus = {
  orgName: 'Example Organization',
  services: [
    { id: 1, name: 'Payments', health: 'OPERATIONAL', uptimePercent: 99.95 },
    { id: 2, name: 'Search', health: 'DEGRADED', uptimePercent: 98.5 },
    { id: 3, name: 'Checkout', health: 'MAJOR_OUTAGE', uptimePercent: 95.1 },
  ],
  activeIncidents: [],
  resolvedIncidents: [],
}

function renderStatus(body = baseStatus, slug = 'example-org') {
  const fetchMock = mockFetch({
    [`GET /api/public/status/${slug}`]: { body },
  })
  const router = createMemoryRouter(routes, {
    initialEntries: [`/status/${slug}`],
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  render(
    <ThemeProvider>
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  )

  return fetchMock
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('Public status page', () => {
  it('shows all three service health states', async () => {
    renderStatus()

    const components = await screen.findByRole('heading', { name: 'Components' })
    const section = components.closest('section')!

    expect(within(section).getByText('Operational')).toBeInTheDocument()
    expect(within(section).getByText('Degraded')).toBeInTheDocument()
    expect(within(section).getByText('Major outage')).toBeInTheDocument()
    expect(within(section).getByText('Payments')).toBeInTheDocument()
    expect(within(section).getByText('Search')).toBeInTheDocument()
    expect(within(section).getByText('Checkout')).toBeInTheDocument()
  })

  it('shows the correct overall status when everything works', async () => {
    renderStatus({
      ...baseStatus,
      services: [baseStatus.services[0]],
    })
    expect(
      await screen.findByRole('heading', { name: 'All systems operational' }),
    ).toBeInTheDocument()
  })

  it('shows partial outage for degraded services', async () => {
    renderStatus({
      ...baseStatus,
      services: [baseStatus.services[1]],
    })
    expect(
      await screen.findByRole('heading', { name: 'Partial outage' }),
    ).toBeInTheDocument()
  })

  it('shows major outage for an unavailable service', async () => {
    renderStatus()
    expect(
      await screen.findByRole('heading', { name: 'Major outage' }),
    ).toBeInTheDocument()
  })

  it('shows not found for an unknown organization slug', async () => {
    mockFetch({})
    const router = createMemoryRouter(routes, {
      initialEntries: ['/status/unknown-org'],
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <ThemeProvider>
        <QueryClientProvider client={client}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: 'Status page not found' }),
    ).toBeInTheDocument()
  })
})
