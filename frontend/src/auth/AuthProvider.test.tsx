import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { apiFetch } from '@/api/client'
import { clearToken, readToken, writeToken } from '@/api/token'
import { AuthProvider, useAuth } from '@/auth/AuthProvider'
import { NotificationBell } from '@/components/shell/NotificationBell'
import { IncidentsPage } from '@/pages/IncidentsPage'

// One session's data must never reach the next one (FR-01.5), and an answer about an old token must
// not change the current session (FR-01). Two people, A and B, each in an org of their own.

const account = (name: 'A' | 'B') => ({
  token: `token-${name}`,
  expiresAt: '2027-01-01T00:00:00Z',
  user: { id: name === 'A' ? 1 : 2, email: `${name}@example.test`, displayName: name, role: 'ADMIN' },
  org: { id: name === 'A' ? 1 : 2, name: `Org ${name}`, slug: name.toLowerCase() },
})

const respond = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status })

const incident = (title: string) => ({
  id: 123, reference: 'INC-1', title, serviceName: 'A service', severity: 'SEV1', status: 'OPEN', createdAt: '2026-11-07T00:00:00Z', responseDueAt: '2026-11-07T00:15:00Z', resolutionDueAt: '2026-11-07T04:00:00Z',
})

function later<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const bearer = (init?: RequestInit) => (init?.headers as Record<string, string> | undefined)?.Authorization

/** Answers login and logout for A and B; `api` answers everything else. */
function stubFetch(api: (path: string, init?: RequestInit) => Promise<Response> | Response) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path === '/api/auth/login') return respond(account(JSON.parse(String(init?.body)).email[0]))
    if (path === '/api/auth/logout') return respond(null, 204)
    return api(path, init)
  }))
}

function Harness({ page }: { page?: 'incidents' | 'notifications' }) {
  const auth = useAuth()
  return (
    <>
      <output data-testid="session">{auth.status}:{auth.org?.name}</output>
      <button onClick={() => void auth.login('A@example.test', 'password')}>Login A</button>
      <button onClick={() => void auth.login('B@example.test', 'password')}>Login B</button>
      <button onClick={() => void auth.logout()}>Logout</button>
      {page === 'incidents' && auth.status === 'authenticated' && <IncidentsPage />}
      {page === 'notifications' && auth.status === 'authenticated' && <NotificationBell />}
    </>
  )
}

function renderApp(page?: 'incidents' | 'notifications') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
  render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <AuthProvider>
          <Harness page={page} />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  )
  return client
}

async function switchFromAToB() {
  await userEvent.click(screen.getByText('Logout'))
  await waitFor(() => expect(screen.getByTestId('session')).toHaveTextContent('anonymous:'))
  await userEvent.click(screen.getByText('Login B'))
  await waitFor(() => expect(screen.getByTestId('session')).toHaveTextContent('authenticated:Org B'))
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('AuthProvider sessions', () => {
  it("shows none of A's incidents to B while B's own are loading", async () => {
    const incidentsOfB = later<Response>()
    stubFetch((path, init) => {
      if (path !== '/api/incidents') throw new Error(`Unexpected request ${path}`)
      return bearer(init) === 'Bearer token-B' ? incidentsOfB.promise : respond([incident('Private incident of Org A')])
    })
    const client = renderApp('incidents')
    await userEvent.click(screen.getByText('Login A'))
    await screen.findByRole('link', { name: 'Private incident of Org A' })

    await switchFromAToB()

    expect(screen.queryByRole('link', { name: 'Private incident of Org A' })).not.toBeInTheDocument()
    expect(client.getQueryData(['incidents'])).toBeUndefined()
    await act(async () => incidentsOfB.resolve(respond([])))
  })

  it("shows none of A's notifications to B while B's own are loading", async () => {
    const notificationsOfB = later<Response>()
    stubFetch((path, init) => {
      if (path !== '/api/notifications') throw new Error(`Unexpected request ${path}`)
      if (bearer(init) === 'Bearer token-B') return notificationsOfB.promise
      return respond({ unreadCount: 1, notifications: [
        { id: 1, incidentId: null, message: 'For A only', createdAt: '2026-11-07T00:00:00Z', readAt: null },
      ] })
    })
    renderApp('notifications')
    await userEvent.click(screen.getByText('Login A'))
    await screen.findByRole('button', { name: 'Notifications, 1 unread' })

    await switchFromAToB()

    expect(screen.queryByRole('button', { name: 'Notifications, 1 unread' })).not.toBeInTheDocument()
    await act(async () => notificationsOfB.resolve(respond({ unreadCount: 0, notifications: [] })))
  })

  it("asks again for B instead of reusing A's request still in flight", async () => {
    const incidentsOfA = later<Response>()
    const tokens: (string | undefined)[] = []
    stubFetch((path, init) => {
      if (path !== '/api/incidents') throw new Error(`Unexpected request ${path}`)
      tokens.push(bearer(init))
      return bearer(init) === 'Bearer token-B' ? respond([]) : incidentsOfA.promise
    })
    renderApp('incidents')
    await userEvent.click(screen.getByText('Login A'))
    await waitFor(() => expect(tokens).toEqual(['Bearer token-A']))

    await switchFromAToB()
    await waitFor(() => expect(tokens).toEqual(['Bearer token-A', 'Bearer token-B']))
    await act(async () => incidentsOfA.resolve(respond([incident('Late incident of Org A')])))

    expect(screen.queryByRole('link', { name: 'Late incident of Org A' })).not.toBeInTheDocument()
  })

  it("does not sign B out when a request made with A's token comes back 401 late", async () => {
    const oldRequest = later<Response>()
    stubFetch((path) => {
      if (path !== '/api/slow') throw new Error(`Unexpected request ${path}`)
      return oldRequest.promise
    })
    renderApp()
    await userEvent.click(screen.getByText('Login A'))
    await waitFor(() => expect(readToken()).toBe('token-A'))
    const pending = apiFetch('/slow', z.unknown()).catch((error: Error) => error)

    await switchFromAToB()
    await act(async () => {
      oldRequest.resolve(respond({ message: 'Revoked' }, 401))
      await pending
    })

    expect(readToken()).toBe('token-B')
    expect(screen.getByTestId('session')).toHaveTextContent('authenticated:Org B')
  })

  it("keeps B signed in as B when the start-up check of A's saved token answers after B's sign-in", async () => {
    const meOfA = later<Response>()
    writeToken('token-A')
    stubFetch((path) => {
      if (path !== '/api/auth/me') throw new Error(`Unexpected request ${path}`)
      return meOfA.promise
    })
    renderApp()
    await userEvent.click(screen.getByText('Login B'))
    await waitFor(() => expect(screen.getByTestId('session')).toHaveTextContent('authenticated:Org B'))

    await act(async () => meOfA.resolve(respond({ user: account('A').user, org: account('A').org })))

    expect(readToken()).toBe('token-B')
    expect(screen.getByTestId('session')).toHaveTextContent('authenticated:Org B')
  })
})
