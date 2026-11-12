import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '@/api/client'
import {
  decideSuggestedAction,
  generateIncidentDraft,
  getLatestInvestigation,
  runInvestigation,
} from '@/api/ai'
import { useAuth } from '@/auth/AuthProvider'
import { IncidentAIPanel } from './IncidentAIPanel'

vi.mock('@/api/ai', () => ({
  decideSuggestedAction: vi.fn(),
  generateIncidentDraft: vi.fn(),
  getLatestInvestigation: vi.fn(),
  runInvestigation: vi.fn(),
}))

vi.mock('@/auth/AuthProvider', () => ({
  useAuth: vi.fn(),
}))

vi.mock('@/auth/roles', () => ({
  can: (role: string | undefined, permission: string) =>
    permission === 'respond' && (role === 'ENGINEER' || role === 'ADMIN'),
}))

const investigation = {
  id: 12,
  summary: 'The payment gateway is timing out.',
  hypotheses: [
    {
      rank: 1,
      title: 'Gateway connection pool exhausted',
      confidence: 0.9,
      evidence: [{ kind: 'LOG', text: 'Connection timeout detected.' }],
    },
  ],
  suggestedActions: [
    {
      id: 5,
      text: 'Check the payment gateway connection pool.',
      decision: null,
      decidedBy: null,
      decidedAt: null,
    },
  ],
  createdAt: '2026-11-12T10:00:00Z',
}

function renderPanel(role = 'ENGINEER') {
  vi.mocked(useAuth).mockReturnValue({
    user: { id: 1, role },
  } as ReturnType<typeof useAuth>)

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <IncidentAIPanel incidentId={7} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('IncidentAIPanel', () => {
  it('shows investigation results and lets an Engineer accept a suggested action', async () => {
    vi.mocked(getLatestInvestigation).mockResolvedValue(investigation)
    vi.mocked(decideSuggestedAction).mockResolvedValue(undefined)

    renderPanel()

    expect(await screen.findByText('The payment gateway is timing out.')).toBeInTheDocument()
    expect(screen.getByText(/Gateway connection pool exhausted/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    await waitFor(() => {
      expect(decideSuggestedAction).toHaveBeenCalledWith(12, 5, 'ACCEPTED')
    })
  })

  it('allows an Engineer to start an investigation', async () => {
    vi.mocked(getLatestInvestigation).mockRejectedValue(new ApiRequestError(404, 'Not found'))
    vi.mocked(runInvestigation).mockResolvedValue(investigation)

    renderPanel()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Investigate incident' }),
    )

    await waitFor(() => {
      expect(runInvestigation).toHaveBeenCalledWith(7)
    })
  })

  it('shows results to a Viewer but hides investigation and decision buttons', async () => {
    vi.mocked(getLatestInvestigation).mockResolvedValue(investigation)

    renderPanel('VIEWER')

    expect(await screen.findByText('The payment gateway is timing out.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Investigate incident' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('shows an unavailable message when investigation returns 503', async () => {
    vi.mocked(getLatestInvestigation).mockRejectedValue(
      new ApiRequestError(503, 'AI service unavailable'),
    )

    renderPanel()

    expect(
      await screen.findByText(/AI is currently unavailable/i),
    ).toBeInTheDocument()
  })

  it('generates an editable Catch me up summary draft', async () => {
    vi.mocked(getLatestInvestigation).mockRejectedValue(new ApiRequestError(404, 'Not found'))
    vi.mocked(generateIncidentDraft).mockResolvedValue({
      kind: 'SUMMARY',
      text: 'Payments are failing because the gateway is timing out.',
      postmortem: null,
      generatedAt: '2026-11-12T10:00:00Z',
    })

    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: 'Catch me up' }))

    const draft = await screen.findByRole('textbox', { name: 'AI summary draft' })
    expect(draft).toHaveValue('Payments are failing because the gateway is timing out.')

    fireEvent.change(draft, { target: { value: 'Edited summary' } })

    expect(draft).toHaveValue('Edited summary')
    expect(generateIncidentDraft).toHaveBeenCalledWith(7, 'SUMMARY')
  })
})
