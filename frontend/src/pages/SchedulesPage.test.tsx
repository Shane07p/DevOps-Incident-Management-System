import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { mockFetch, payments, users } from '@/test/fetchMock'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: { id: 3, username: 'viewer', displayName: 'Dev Viewer', role: 'VIEWER' },
}

// The clock is 11:30 IST on Wed 28 Oct: engineer is on until 17:00 IST, then admin until 21:00 IST.
const NOW = '2026-10-28T06:00:00Z'
const engineerShift = { id: 11, responderId: 2, responderUsername: 'engineer', startTime: '2026-10-28T03:30:00Z', endTime: '2026-10-28T11:30:00Z' }
const adminShift = { id: 12, responderId: 1, responderUsername: 'admin', startTime: '2026-10-28T11:30:00Z', endTime: '2026-10-28T15:30:00Z' }
const schedule = { serviceId: 1, fallbackContactId: 1, fallbackContactUsername: 'admin', shifts: [engineerShift, adminShift] }

type Routes = Parameters<typeof mockFetch>[0]

function renderSchedules(role: Role, extraRoutes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: signedIn[role] },
    'GET /api/services': { body: [payments] },
    'GET /api/services/1/on-call': { body: schedule },
    'GET /api/users': { body: users },
    ...extraRoutes,
  })
  const router = createMemoryRouter(routes, { initialEntries: ['/schedules'] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
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

const puts = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')
const sentBody = (fetchMock: ReturnType<typeof mockFetch>) => JSON.parse(String(puts(fetchMock)[0][1]?.body))

// Fills the add-shift form; times are what an Admin types, in IST.
async function fillShift(responder: string, start: string, end: string) {
  const form = await screen.findByRole('form', { name: 'Add a shift' })
  await userEvent.click(within(form).getByLabelText('Responder'))
  await userEvent.click(await screen.findByRole('option', { name: responder }))
  fireEvent.change(within(form).getByLabelText('Starts (IST)'), { target: { value: start } })
  fireEvent.change(within(form).getByLabelText('Ends (IST)'), { target: { value: end } })
  await userEvent.click(within(form).getByRole('button', { name: 'Add shift' }))
  return form
}

beforeEach(() => {
  // Only Date is faked, so timers and user events run normally.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(NOW))
})

afterEach(() => {
  vi.useRealTimers()
  clearToken()
  vi.unstubAllGlobals()
})

describe('SchedulesPage', () => {
  it('shows who is on call now, who is next, and the fallback contact, in IST', async () => {
    renderSchedules('VIEWER')

    const now = (await screen.findByText('On call now')).parentElement!
    expect(now).toHaveTextContent('engineer')
    expect(now).toHaveTextContent('Until 28 Oct, 17:00 IST')
    expect(screen.getByText('Next shift').parentElement).toHaveTextContent('adminFrom 28 Oct, 17:00 IST')
    expect(screen.getByText('Fallback contact').parentElement).toHaveTextContent('admin')
    expect(screen.getByRole('figure', { name: 'Shifts over the next 7 days, in IST' })).toHaveTextContent('Wed 28')
  })

  it('lets a Viewer read the schedule without changing it', async () => {
    renderSchedules('VIEWER')

    await screen.findByText('On call now')
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Add a shift' })).not.toBeInTheDocument()
  })

  // TC-FR-15.1b: an overlapping shift is refused on the form, and nothing is sent.
  it('shows an overlapping shift as an error on the form and sends nothing', async () => {
    const fetchMock = renderSchedules('ADMIN', { 'PUT /api/services/1/on-call': { body: schedule } })

    const form = await fillShift('Dev Engineer (engineer)', '2026-10-28T10:00', '2026-10-28T12:00')

    expect(await within(form).findByRole('alert')).toHaveTextContent(
      "This overlaps engineer's shift, 28 Oct, 09:00 IST to 28 Oct, 17:00 IST.",
    )
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('refuses a shift that ends before it starts', async () => {
    const fetchMock = renderSchedules('ADMIN')

    const form = await fillShift('Dev Engineer (engineer)', '2026-10-29T17:00', '2026-10-29T09:00')

    expect(await within(form).findByText('End the shift after it starts')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('adds a shift by sending the whole schedule with the new shift in UTC', async () => {
    const fetchMock = renderSchedules('ADMIN', { 'PUT /api/services/1/on-call': { body: schedule } })

    await fillShift('Dev Engineer (engineer)', '2026-10-29T09:00', '2026-10-29T17:00')

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(puts(fetchMock)[0][0]).toBe('/api/services/1/on-call')
    expect(sentBody(fetchMock)).toEqual({
      fallbackContactId: 1,
      shifts: [
        { responderId: 2, startTime: engineerShift.startTime, endTime: engineerShift.endTime },
        { responderId: 1, startTime: adminShift.startTime, endTime: adminShift.endTime },
        { responderId: 2, startTime: '2026-10-29T03:30:00.000Z', endTime: '2026-10-29T11:30:00.000Z' },
      ],
    })
  })

  it("shows the server's overlap error on the form", async () => {
    renderSchedules('ADMIN', {
      'PUT /api/services/1/on-call': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { shifts: 'Shifts cannot overlap for the same service' } },
      },
    })

    const form = await fillShift('Dev Admin (admin)', '2026-10-29T09:00', '2026-10-29T17:00')

    expect(await within(form).findByRole('alert')).toHaveTextContent('Shifts cannot overlap for the same service')
  })

  it('removes a shift only after confirmation', async () => {
    const fetchMock = renderSchedules('ADMIN', { 'PUT /api/services/1/on-call': { body: { ...schedule, shifts: [adminShift] } } })

    await userEvent.click(await screen.findByRole('button', { name: "Remove engineer's shift from 28 Oct, 09:00 IST" }))
    const confirm = await screen.findByRole('dialog', { name: 'Remove this shift?' })
    expect(puts(fetchMock)).toHaveLength(0)
    await userEvent.click(within(confirm).getByRole('button', { name: 'Remove shift' }))

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(sentBody(fetchMock)).toEqual({
      fallbackContactId: 1,
      shifts: [{ responderId: 1, startTime: adminShift.startTime, endTime: adminShift.endTime }],
    })
  })

  it('asks for a fallback contact before the first shift, then saves it', async () => {
    const empty = { serviceId: 1, fallbackContactId: null, fallbackContactUsername: null, shifts: [] }
    const fetchMock = renderSchedules('ADMIN', {
      'GET /api/services/1/on-call': { body: empty },
      'PUT /api/services/1/on-call': { body: { ...empty, fallbackContactId: 2, fallbackContactUsername: 'engineer' } },
    })

    expect(await screen.findByText(/Set a fallback contact first/)).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Add a shift' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Contact')).toBeEnabled())
    await userEvent.click(screen.getByLabelText('Contact'))
    await userEvent.click(await screen.findByRole('option', { name: 'Dev Engineer (engineer)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save fallback' }))

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(sentBody(fetchMock)).toEqual({ fallbackContactId: 2, shifts: [] })
    expect(await screen.findByRole('form', { name: 'Add a shift' })).toBeInTheDocument()
  })
})
