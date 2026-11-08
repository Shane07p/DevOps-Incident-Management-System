import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, users } from '@/test/fetchMock'

const admin = users[0]
const engineer = users[1]

const entry = (id: number, overrides: Record<string, unknown> = {}) => ({
  id,
  actorDisplayName: 'Dev Admin',
  action: 'SERVICE_UPDATED',
  itemType: 'SERVICE',
  itemId: 1,
  details: 'Payments: criticality HIGH → CRITICAL',
  recordedAt: '2026-11-07T06:00:00Z',
  ...overrides,
})

/** Signs in as `who`, answers every audit request with `page`, and records the audit URLs asked for. */
function renderAudit(path = '/audit', who = admin, page = { content: [entry(1)], page: 0, size: 50, totalElements: 1 }) {
  writeToken('test-token')
  const auditUrls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    let body: unknown
    if (url === '/api/auth/me') body = { user: who, org: devOrg }
    else if (url === '/api/users') body = users
    else if (url.startsWith('/api/audit')) {
      auditUrls.push(url)
      body = page
    }
    else return new Response(JSON.stringify({ message: `No mock for ${url}` }), { status: 404 })
    return new Response(JSON.stringify(body), { status: 200 })
  }))
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <ThemeProvider>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AuthProvider>
          <TooltipProvider>
            <RouterProvider router={router} />
          </TooltipProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return { auditUrls, router }
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('AuditLogPage', () => {
  it('TC-US-17b: shows each change as the API orders it, in IST, with no edit or delete controls', async () => {
    renderAudit('/audit', admin, {
      content: [
        entry(2, { actorDisplayName: null, action: 'RUNBOOK_CREATED', itemType: 'RUNBOOK', itemId: 4, details: '"Gateway timeouts" for Payments, 3 steps', recordedAt: '2026-11-07T06:00:00Z' }),
        entry(1, { recordedAt: '2026-11-06T18:45:00Z' }),
      ],
      page: 0,
      size: 50,
      totalElements: 2,
    })

    const rows = (await screen.findAllByRole('row')).slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('7 Nov, 11:30 IST')
    expect(rows[0]).toHaveTextContent('System')
    expect(rows[0]).toHaveTextContent('Runbook created')
    expect(rows[0]).toHaveTextContent('Runbook #4')
    expect(rows[1]).toHaveTextContent('7 Nov, 00:15 IST')
    expect(rows[1]).toHaveTextContent('Service updated')
    for (const row of rows) {
      expect(within(row).queryAllByRole('button')).toHaveLength(0)
    }
    expect(screen.queryByRole('button', { name: /edit|delete|remove/i })).not.toBeInTheDocument()
  })

  it('TC-US-17a: sends the item, person, and IST dates as filters, and the page', async () => {
    const { auditUrls, router } = renderAudit('/audit', admin, { content: [entry(1)], page: 0, size: 50, totalElements: 120 })
    await screen.findByText('Page 1 of 3 (120 changes)')

    await userEvent.click(screen.getByRole('combobox', { name: 'Item' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Service' }))
    await userEvent.click(screen.getByRole('combobox', { name: 'Changed by' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Dev Engineer' }))
    fireEvent.change(screen.getByLabelText('From (IST)'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('To (IST)'), { target: { value: '2026-11-07' } })
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }))

    // From the start of 1 Nov IST to the end of 7 Nov IST: the API's `to` is exclusive.
    await waitFor(() => expect(auditUrls.at(-1)).toBe(
      '/api/audit?page=1&itemType=SERVICE&actorId=2&from=2026-10-31T18%3A30%3A00.000Z&to=2026-11-07T18%3A30%3A00.000Z',
    ))
    expect(router.state.location.search).toBe('?itemType=SERVICE&actorId=2&from=2026-11-01&to=2026-11-07&page=2')
  })

  it('starts again from the first page when a filter changes', async () => {
    const { auditUrls } = renderAudit('/audit?page=3', admin, { content: [entry(1)], page: 2, size: 50, totalElements: 120 })
    await waitFor(() => expect(auditUrls.at(-1)).toBe('/api/audit?page=2'))

    await userEvent.click(await screen.findByRole('combobox', { name: 'Item' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Tool' }))

    await waitFor(() => expect(auditUrls.at(-1)).toBe('/api/audit?page=0&itemType=TOOL'))
  })

  it('asks nothing when the end date is before the start date, and says so', async () => {
    const { auditUrls } = renderAudit('/audit?from=2026-11-07&to=2026-11-01')

    expect(await screen.findByRole('alert')).toHaveTextContent('The end date is before the start date.')
    expect(auditUrls).toEqual([])
  })

  it('is for Admins only: an Engineer never reaches it', async () => {
    const { auditUrls, router } = renderAudit('/audit', engineer)

    // Sent home once the session shows the role.
    await waitFor(() => expect(router.state.location.pathname).toBe('/home'))
    expect(screen.queryByRole('heading', { name: 'Audit log' })).not.toBeInTheDocument()
    expect(auditUrls).toEqual([])
  })
})
