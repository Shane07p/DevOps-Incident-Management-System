import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '@/api/client'
import { generateIncidentDraft } from '@/api/ai'
import { postPublicUpdate } from '@/api/publicStatus'
import { PublicUpdates } from './PublicUpdateForm'

vi.mock('@/api/ai', () => ({
  generateIncidentDraft: vi.fn(),
}))

vi.mock('@/api/publicStatus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/publicStatus')>()
  return {
    ...actual,
    postPublicUpdate: vi.fn(),
  }
})

function renderPublicUpdates() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <PublicUpdates incidentId={7} mayPublish />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('AI public update draft', () => {
  it('fills the public message with an editable AI draft without publishing it', async () => {
    vi.mocked(generateIncidentDraft).mockResolvedValue({
      kind: 'STATUS_UPDATE',
      text: 'We are investigating payment delays.',
      postmortem: null,
      generatedAt: '2026-11-12T10:00:00Z',
    })

    renderPublicUpdates()

    await userEvent.click(
      screen.getByRole('button', { name: 'Draft update (AI)' }),
    )

    const message = await screen.findByRole('textbox', {
      name: 'Public message',
    })

    expect(message).toHaveValue('We are investigating payment delays.')
    expect(generateIncidentDraft).toHaveBeenCalledWith(7, 'STATUS_UPDATE')
    expect(postPublicUpdate).not.toHaveBeenCalled()

    fireEvent.change(message, {
      target: { value: 'Edited public update.' },
    })

    expect(message).toHaveValue('Edited public update.')
    expect(postPublicUpdate).not.toHaveBeenCalled()
  })

  it('shows an unavailable message when AI draft generation returns 503', async () => {
    vi.mocked(generateIncidentDraft).mockRejectedValue(
      new ApiRequestError(503, 'AI service unavailable'),
    )

    renderPublicUpdates()

    await userEvent.click(
      screen.getByRole('button', { name: 'Draft update (AI)' }),
    )

    expect(
      await screen.findByText(/AI is currently unavailable/i),
    ).toBeInTheDocument()

    expect(screen.getByRole('textbox', { name: 'Public message' })).toBeInTheDocument()
    expect(postPublicUpdate).not.toHaveBeenCalled()
  })
})
