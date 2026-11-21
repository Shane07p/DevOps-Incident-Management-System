import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FollowUp } from '@/api/postmortems'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, users } from '@/test/fetchMock'

const corrective: FollowUp = {
  id: 10, incidentId: 7, incidentReference: 'INC-000007', title: 'Add a processor timeout',
  kind: 'CORRECTIVE', owner: { id: 1, displayName: 'Dev Admin' }, dueDate: '2026-10-01',
  status: 'OPEN', overdue: true, completedAt: null, createdAt: '2026-09-30T10:00:00Z',
}
const preventive: FollowUp = {
  ...corrective, id: 11, incidentId: 8, incidentReference: 'INC-000008', title: 'Run a recovery exercise',
  kind: 'PREVENTIVE', owner: { id: 2, displayName: 'Dev Engineer' }, dueDate: '2026-12-01',
  status: 'IN_PROGRESS', overdue: false,
}
type MockRoutes = Parameters<typeof mockFetch>[0]

function renderPage({ role = 'ADMIN', path = '/follow-ups', overrides = {} }: { role?: Role; path?: string; overrides?: MockRoutes } = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: { ...users[0], role }, org: devOrg } },
    'GET /api/follow-ups': { body: [preventive, corrective] },
    'GET /api/notifications/unread-count': { body: { count: 0 } },
    ...overrides,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <ThemeProvider><QueryClientProvider client={client}><TooltipProvider>
      <AuthProvider><RouterProvider router={router} /></AuthProvider>
    </TooltipProvider></QueryClientProvider></ThemeProvider>,
  )
  return { fetchMock, router }
}

const listCalls = (fetchMock: ReturnType<typeof mockFetch>) => fetchMock.mock.calls.filter(([input, init]) =>
  (init?.method ?? 'GET') === 'GET' && String(input).split('?')[0] === '/api/follow-ups')
const lastQuery = (fetchMock: ReturnType<typeof mockFetch>) => new URL(String(listCalls(fetchMock).at(-1)?.[0]), 'http://test').searchParams

afterEach(() => { clearToken(); vi.unstubAllGlobals() })

describe('Org follow-ups page', () => {
  it('renders the routed list in soonest-due order with incident links, kinds, owners and dates', async () => {
    renderPage({ role: 'VIEWER' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Follow-ups' })).toBeInTheDocument()
    expect(screen.getByText('Actions from every incident')).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Follow-ups' })
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['Title', 'Incident', 'Kind', 'Owner', 'Due date', 'Status'])
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent(corrective.title)
    expect(rows[1]).toHaveTextContent(preventive.title)
    expect(within(rows[0]).getByRole('link', { name: corrective.incidentReference })).toHaveAttribute('href', '/incidents/7')
    expect(rows[0]).toHaveTextContent('Corrective')
    expect(rows[0]).toHaveTextContent('Dev Admin')
    expect(rows[1]).toHaveTextContent('Preventive')
    expect(rows[1]).toHaveTextContent('Dev Engineer')
    expect(within(rows[0]).getByText('1 Oct 2026')).toHaveAttribute('dateTime', '2026-10-01')
  })

  it('flags overdue work from the API but never labels a completed item overdue', async () => {
    renderPage({ role: 'VIEWER', overrides: { 'GET /api/follow-ups': { body: [corrective, { ...preventive, overdue: true, status: 'DONE' }] } } })
    const table = await screen.findByRole('table', { name: 'Follow-ups' })
    expect(within(table).getAllByText('Overdue')).toHaveLength(1)
    expect(within(table).getByText('Done')).toBeInTheDocument()
  })

  it('sends Mine as owner=me and keeps it in the URL', async () => {
    const user = userEvent.setup()
    const { fetchMock, router } = renderPage()
    await user.click(await screen.findByRole('combobox', { name: 'Owner' }))
    await user.click(screen.getByRole('option', { name: 'Mine' }))
    await waitFor(() => expect(lastQuery(fetchMock).get('owner')).toBe('me'))
    expect(router.state.location.search).toBe('?owner=me')
    await user.click(screen.getByRole('combobox', { name: 'Owner' }))
    await user.click(screen.getByRole('option', { name: 'Everyone' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(lastQuery(fetchMock).has('owner')).toBe(false)
  })

  it('sends the selected status and restores all URL filters on entry', async () => {
    const user = userEvent.setup()
    const { fetchMock, router } = renderPage({ path: '/follow-ups?owner=me&status=OPEN&overdue=true' })
    expect(await screen.findByRole('checkbox', { name: 'Overdue only' })).toBeChecked()
    expect(screen.getByRole('combobox', { name: 'Owner' })).toHaveTextContent('Mine')
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveTextContent('Open')
    await user.click(screen.getByRole('combobox', { name: 'Status' }))
    await user.click(screen.getByRole('option', { name: 'In progress' }))
    await waitFor(() => expect(lastQuery(fetchMock).get('status')).toBe('IN_PROGRESS'))
    expect(lastQuery(fetchMock).get('owner')).toBe('me')
    expect(lastQuery(fetchMock).get('overdue')).toBe('true')
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('IN_PROGRESS')
  })

  it('sends overdue=true only while Overdue only is selected', async () => {
    const user = userEvent.setup()
    const { fetchMock, router } = renderPage()
    const control = await screen.findByRole('checkbox', { name: 'Overdue only' })
    await user.click(control)
    await waitFor(() => expect(lastQuery(fetchMock).get('overdue')).toBe('true'))
    expect(router.state.location.search).toBe('?overdue=true')
    await user.click(control)
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(lastQuery(fetchMock).has('overdue')).toBe(false)
  })

  it('supports a user-id owner deep link accepted by the existing API', async () => {
    const { fetchMock } = renderPage({ path: '/follow-ups?owner=2' })
    expect(await screen.findByRole('table', { name: 'Follow-ups' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Owner' })).toHaveTextContent('Dev Engineer')
    expect(lastQuery(fetchMock).get('owner')).toBe('2')
  })

  it('gives Viewers a readable list and filters without status controls', async () => {
    renderPage({ role: 'VIEWER' })
    expect(await screen.findByText(corrective.title)).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: /^Status for / })).not.toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Status' })).toBeInTheDocument()
    expect(screen.getByText('Open')).toBeInTheDocument()
  })

  it.each(['ADMIN', 'ENGINEER'] as const)('lets %s change only the status and refetches the list', async (role) => {
    const user = userEvent.setup()
    let saved = false
    const { fetchMock } = renderPage({ role, overrides: {
      'GET /api/follow-ups': () => ({ body: [{ ...corrective, status: saved ? 'DONE' : 'OPEN', overdue: !saved }] }),
      'PUT /api/follow-ups/10': () => { saved = true; return { body: { ...corrective, status: 'DONE', overdue: false } } },
    } })
    await user.click(await screen.findByRole('combobox', { name: `Status for ${corrective.title}` }))
    await user.click(screen.getByRole('option', { name: 'Done' }))
    await waitFor(() => expect(screen.getByRole('combobox', { name: `Status for ${corrective.title}` })).toHaveTextContent('Done'))
    expect(listCalls(fetchMock).length).toBeGreaterThanOrEqual(2)
    const put = fetchMock.mock.calls.find(([input, init]) => input === '/api/follow-ups/10' && init?.method === 'PUT')
    expect(JSON.parse(String(put?.[1]?.body))).toEqual({ status: 'DONE' })
    expect(screen.queryByText('Overdue')).not.toBeInTheDocument()
  })

  it('shows the unfiltered empty state', async () => {
    renderPage({ overrides: { 'GET /api/follow-ups': { body: [] } } })
    expect(await screen.findByText('No follow-ups yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument()
  })

  it('shows the filtered empty state and Clear filters reloads the unfiltered list', async () => {
    const user = userEvent.setup()
    const { fetchMock, router } = renderPage({ path: '/follow-ups?owner=me&status=DONE&overdue=true', overrides: {
      'GET /api/follow-ups?owner=me&status=DONE&overdue=true': { body: [] },
    } })
    expect(await screen.findByText('No follow-ups match these filters')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText(corrective.title)).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(lastQuery(fetchMock).size).toBe(0)
  })

  it('shows an API error with a retry action', async () => {
    const user = userEvent.setup()
    let failed = true
    renderPage({ overrides: { 'GET /api/follow-ups': () => failed ? { status: 503, body: { message: 'Database unavailable' } } : { body: [corrective] } } })
    expect(await screen.findByText('Follow-ups could not be loaded')).toBeInTheDocument()
    expect(screen.getByText('Database unavailable')).toBeInTheDocument()
    failed = false
    await user.click(screen.getByRole('button', { name: /retry|try again/i }))
    expect(await screen.findByText(corrective.title)).toBeInTheDocument()
  })

  it('keeps the previous status and the list visible when an update fails', async () => {
    const user = userEvent.setup()
    renderPage({ overrides: { 'PUT /api/follow-ups/10': { status: 403, body: { message: 'Permission denied' } } } })
    await user.click(await screen.findByRole('combobox', { name: `Status for ${corrective.title}` }))
    await user.click(screen.getByRole('option', { name: 'Done' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Permission denied')
    expect(screen.getByRole('combobox', { name: `Status for ${corrective.title}` })).toHaveTextContent('Open')
    expect(screen.getByText(corrective.title)).toBeInTheDocument()
  })
})
