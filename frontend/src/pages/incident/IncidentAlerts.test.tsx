import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { IncidentAlerts } from './IncidentAlerts'

const alert = (id: number, incidentId: number, correlationKey: string | null) => ({
  id, source: 'grafana', serviceName: 'Payments', incidentId, incidentReference: `INC-${incidentId}`, assigneeId: null,
  status: 'ATTACHED', correlationKey, createdAt: '2026-11-19T08:00:00Z', count: 1,
})

function renderAlerts(routes: Parameters<typeof mockFetch>[0]) {
  mockFetch(routes)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <IncidentAlerts incidentId={7} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('IncidentAlerts', () => {
  it("lists only this incident's alerts, with their correlation key when they have one", async () => {
    renderAlerts({ 'GET /api/alerts': { body: [alert(1, 7, 'grafana-Checkout errors-payments'), alert(2, 7, null), alert(3, 8, 'other')] } })

    expect(await screen.findByText('grafana-Checkout errors-payments')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.queryByText('other')).not.toBeInTheDocument()
    expect(screen.getByRole('heading')).toHaveTextContent('Alerts 2')
  })

  it('says the incident was declared by hand when no alert belongs to it', async () => {
    renderAlerts({ 'GET /api/alerts': { body: [alert(3, 8, null)] } })

    expect(await screen.findByText('No alert opened or joined this incident; it was declared by hand.')).toBeInTheDocument()
  })

  it('points to the alert inbox when the alerts do not load', async () => {
    renderAlerts({ 'GET /api/alerts': { status: 500, body: { status: 500, message: 'Alerts are unavailable' } } })

    expect(await screen.findByText('The alerts did not load. The alert inbox lists them too.')).toBeInTheDocument()
  })
})
