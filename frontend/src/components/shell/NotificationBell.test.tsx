import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { NotificationBell } from './NotificationBell'

const unreadAssignment = {
  id: 5,
  incidentId: 7,
  message: 'You have been assigned to incident INC-7: Checkout failing',
  createdAt: '2026-10-31T06:00:00Z',
  readAt: null,
}
const readEscalation = {
  id: 4,
  incidentId: 6,
  message: 'Incident INC-6 was escalated to you (level 1): Payments slow',
  createdAt: '2026-10-30T06:00:00Z',
  readAt: '2026-10-30T06:05:00Z',
}

function IncidentStub() {
  return <p>Incident {useParams().id}</p>
}

function renderBell() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter([
    { path: '/', element: <NotificationBell /> },
    { path: '/incidents/:id', element: <IncidentStub /> },
  ])
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

const posts = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('NotificationBell', () => {
  it('shows how many notifications are unread', async () => {
    mockFetch({ 'GET /api/notifications': { body: { notifications: [unreadAssignment, readEscalation], unreadCount: 1 } } })
    renderBell()

    const bell = await screen.findByRole('button', { name: 'Notifications, 1 unread' })
    expect(bell).toHaveTextContent('1')
  })

  it('shows no count when everything is read', async () => {
    mockFetch({ 'GET /api/notifications': { body: { notifications: [readEscalation], unreadCount: 0 } } })
    renderBell()

    expect(await screen.findByRole('button', { name: 'Notifications' })).toHaveTextContent('')
  })

  it('caps a large count at 9+', async () => {
    mockFetch({ 'GET /api/notifications': { body: { notifications: [], unreadCount: 12 } } })
    renderBell()

    expect(await screen.findByRole('button', { name: 'Notifications, 12 unread' })).toHaveTextContent('9+')
  })

  it('marks an unread notification read when it is opened, and goes to its incident', async () => {
    const fetchMock = mockFetch({
      'GET /api/notifications': { body: { notifications: [unreadAssignment, readEscalation], unreadCount: 1 } },
      'POST /api/notifications/5/read': { body: { ...unreadAssignment, readAt: '2026-10-31T07:00:00Z' } },
    })
    renderBell()

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    await userEvent.click(await screen.findByRole('button', { name: /Checkout failing/ }))

    expect(await screen.findByText('Incident 7')).toBeInTheDocument()
    await waitFor(() => expect(posts(fetchMock)).toHaveLength(1))
    expect(String(posts(fetchMock)[0][0])).toBe('/api/notifications/5/read')
  })

  it('sends nothing when an already read notification is opened', async () => {
    const fetchMock = mockFetch({
      'GET /api/notifications': { body: { notifications: [unreadAssignment, readEscalation], unreadCount: 1 } },
    })
    renderBell()

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    await userEvent.click(await screen.findByRole('button', { name: /Payments slow/ }))

    expect(await screen.findByText('Incident 6')).toBeInTheDocument()
    expect(posts(fetchMock)).toHaveLength(0)
  })
})
