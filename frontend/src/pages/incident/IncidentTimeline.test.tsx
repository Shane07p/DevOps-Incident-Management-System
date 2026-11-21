import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TimelineEntry } from '@/api/incidents'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { devOrg, mockFetch, users } from '@/test/fetchMock'
import { IncidentTimeline } from './IncidentTimeline'

const entry = (id: number, overrides: Partial<TimelineEntry> = {}): TimelineEntry => ({
  id, eventType: 'COMMENT', actor: 'Dev Engineer', details: `Note ${id}`, recordedAt: '2026-11-19T08:00:00Z', correctsEntryId: null,
  ...overrides,
})

function renderTimeline(entries: TimelineEntry[], routes: Parameters<typeof mockFetch>[0] = {}) {
  writeToken('test-token')
  mockFetch({ 'GET /api/auth/me': { body: { user: users[1], org: devOrg } }, ...routes })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <IncidentTimeline incidentId={7} entries={entries} />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('IncidentTimeline', () => {
  it('links an entry and every correction of it both ways, and names the system when nobody acted', async () => {
    renderTimeline([
      entry(1, { eventType: 'ALERT_ATTACHED', actor: null, details: 'Alert from Grafana' }),
      entry(2, { eventType: 'CORRECTION', details: 'It was Prometheus', correctsEntryId: 1 }),
      entry(3, { eventType: 'CORRECTION', actor: 'Dev Admin', details: 'Both, in fact', correctsEntryId: 1 }),
    ])

    const original = await screen.findByRole('listitem', { name: /^Alert attached by System/ })
    expect(original).toHaveTextContent('Alert attached · System')
    const correctedBy = within(original).getAllByRole('link')
    expect(correctedBy.map((link) => link.getAttribute('href'))).toEqual(['#entry-2', '#entry-3'])
    expect(original).toHaveTextContent(/Corrected by Correction by Dev Engineer.*, Correction by Dev Admin/)
    const correction = screen.getByRole('listitem', { name: /^Correction by Dev Admin/ })
    expect(within(correction).getByRole('link')).toHaveAttribute('href', '#entry-1')
  })

  it("puts the server's reason on the correction, and an unreachable server above its buttons", async () => {
    let attempts = 0
    renderTimeline([entry(1)], {
      'POST /api/incidents/7/timeline/1/corrections': () => {
        attempts += 1
        if (attempts === 1) return { status: 400, body: { status: 400, message: 'Validation failed', errors: { correction: 'Use at most 5,000 characters' } } }
        throw new TypeError('Failed to fetch')
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: /^Correct Comment by Dev Engineer/ }))
    await userEvent.type(screen.getByLabelText('Correction'), 'It was 14:10, not 14:00')
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }))
    expect(await screen.findByText('Use at most 5,000 characters')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Settle could not be reached. Try again in a moment.')

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByLabelText('Correction')).not.toBeInTheDocument()
  })

  it('says when a comment could not be posted because Settle is unreachable', async () => {
    renderTimeline([], {
      'POST /api/incidents/7/comments': () => {
        throw new TypeError('Failed to fetch')
      },
    })

    expect(await screen.findByText('Nothing recorded yet.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Add a comment'), 'Rolled back 4.2')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Settle could not be reached. Try again in a moment.')
  })
})
