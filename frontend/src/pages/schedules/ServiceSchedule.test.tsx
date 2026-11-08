import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { devOrg, users } from '@/test/fetchMock'
import { ServiceSchedule } from './ServiceSchedule'

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

// Each save replaces the whole schedule, so a second change started before the first is saved would
// send the old schedule and undo the first (NFR-11.2).
it('waits for the fallback save before a shift can be added, then sends the saved fallback', async () => {
  writeToken('test-token')
  const initial = { serviceId: 1, fallbackContactId: 1, fallbackContactName: 'Dev Admin', shifts: [] }
  const savedFallback = { ...initial, fallbackContactId: 2, fallbackContactName: 'Dev Engineer' }
  const sent: Array<{ fallbackContactId: number; shifts: unknown[] }> = []
  const finishSave: Array<(response: Response) => void> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path === '/api/services/1/on-call' && init?.method === 'PUT') {
      sent.push(JSON.parse(String(init.body)))
      return new Promise<Response>((resolve) => finishSave.push(resolve))
    }
    const body = path === '/api/auth/me' ? { user: users[0], org: devOrg }
      : path === '/api/users' ? users
      : path === '/api/services/1/on-call' ? initial
      : undefined
    if (body === undefined) throw new Error(`Unexpected request ${path}`)
    return new Response(JSON.stringify(body), { status: 200 })
  }))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <ServiceSchedule serviceId={1} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('button', { name: 'Add shift' })
  await userEvent.click(screen.getByLabelText('Responder'))
  await userEvent.click(await screen.findByRole('option', { name: 'Dev Admin (admin@settle.local)' }))
  fireEvent.change(screen.getByLabelText('Starts (IST)'), { target: { value: '2026-11-08T09:00' } })
  fireEvent.change(screen.getByLabelText('Ends (IST)'), { target: { value: '2026-11-08T10:00' } })
  await userEvent.click(screen.getByLabelText('Contact'))
  await userEvent.click(await screen.findByRole('option', { name: 'Dev Engineer (engineer@settle.local)' }))

  await userEvent.click(screen.getByRole('button', { name: 'Save fallback' }))
  await waitFor(() => expect(sent).toHaveLength(1))
  expect(screen.getByRole('button', { name: 'Add shift' })).toBeDisabled()

  await act(async () => finishSave[0](new Response(JSON.stringify(savedFallback), { status: 200 })))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Add shift' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: 'Add shift' }))

  await waitFor(() => expect(sent).toHaveLength(2))
  expect(sent[1].fallbackContactId).toBe(2)
  expect(sent[1].shifts).toHaveLength(1)
  await act(async () => finishSave[1](new Response(JSON.stringify(savedFallback), { status: 200 })))
})
